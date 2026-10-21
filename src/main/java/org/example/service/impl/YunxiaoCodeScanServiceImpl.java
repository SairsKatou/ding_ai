package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.YunxiaoCodeFileBlobDTO;
import org.example.domain.dto.YunxiaoCodeFileTreeDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.OpenCodeService;
import org.example.service.YunxiaoCodeScanService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 云效分支代码扫描与大模型分析实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoCodeScanServiceImpl implements YunxiaoCodeScanService {

    /**
     * 云效令牌 Header 键名
     */
    private static final String YUNXIAO_TOKEN_HEADER = "x-yunxiao-token";

    /**
     * 云效 Codeup 基础 API 路径
     */
    private static final String CODEUP_BASE_PATH = "/oapi/v1/codeup";

    /**
     * 允许扫描的代码文件后缀列表
     */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".java", ".xml", ".vue", ".js", ".ts", ".sql"
    );

    /**
     * 系统提示词角色
     */
    private static final String ROLE_SYSTEM = "system";

    /**
     * 用户提示词角色
     */
    private static final String ROLE_USER = "user";

    /**
     * 匹配文件树类型的常量
     */
    private static final String TYPE_BLOB = "blob";

    /**
     * 匹配原因：路径匹配
     */
    private static final String MATCH_REASON_PATH = "文件路径直接包含业务关键词";

    /**
     * 匹配原因：源码内容匹配
     */
    private static final String MATCH_REASON_CONTENT = "源码文件内容命中业务关键词";

    @Resource
    private RestTemplate yunxiaoRestTemplate;

    @Resource
    private YunxiaoProperties yunxiaoProperties;

    @Resource
    private YunxiaoDevopsService yunxiaoDevopsService;

    @Resource
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    @Resource
    private OpenCodeService openCodeService;

    /**
     * 扫描指定云效代码库分支的代码，并结合用户提问交由大模型进行深度梳理分析
     *
     * @param param 扫描分析请求参数
     * @return 扫描与分析结果
     */
    @Override
    public YunxiaoCodeScanResultDTO scanAndAnalyzeBranch(YunxiaoCodeScanParam param) {
        if (param == null) {
            throw new IllegalArgumentException("扫描参数不能为空");
        }
        if (StringUtils.isBlank(param.getRepositoryId())) {
            throw new IllegalArgumentException("代码库 ID 或代码库名称不能为空");
        }
        if (StringUtils.isBlank(param.getBranch())) {
            throw new IllegalArgumentException("分支名称不能为空");
        }
        if (StringUtils.isBlank(param.getQuestion())) {
            throw new IllegalArgumentException("提问或分析诉求不能为空");
        }

        // 1. 解析确认实际代码库 ID 与名称
        YunxiaoRepositoryDTO targetRepo = resolveRepository(param.getRepositoryId().trim());
        String actualRepoId = String.valueOf(targetRepo.getId());
        String actualRepoName = targetRepo.getName();
        String branch = param.getBranch().trim();
        String question = param.getQuestion().trim();

        // 确定关键词与最大扫描文件数：本地分析最大 500，远程分析最大 30
        String searchKeyword = resolveKeyword(param.getKeyword(), question);
        int localMaxFiles = param.getMaxFiles() != null && param.getMaxFiles() > Constants.ZERO
                ? Math.min(param.getMaxFiles(), Constants.FIVE_HUNDRED)
                : Constants.FIVE_HUNDRED;
        int remoteMaxFiles = param.getMaxFiles() != null && param.getMaxFiles() > Constants.ZERO
                ? Math.min(param.getMaxFiles(), Constants.THIRTY)
                : Constants.THIRTY;

        log.info("开始扫描云效分支代码: 仓库 [{} ({})], 分支 [{}], 关键词 [{}], 诉求 [{}], 本地最大分析文件数: {}, 远程最大分析文件数: {}",
                actualRepoName, actualRepoId, branch, searchKeyword, question, localMaxFiles, remoteMaxFiles);

        List<YunxiaoCodeSnippetDTO> matchedSnippets = new ArrayList<>();
        String cloneUrl = StringUtils.isNotBlank(targetRepo.getHttpUrlToRepo())
                ? targetRepo.getHttpUrlToRepo()
                : (StringUtils.isNotBlank(targetRepo.getWebUrl()) ? targetRepo.getWebUrl() + ".git" : "");

        // 优先采用【方案 A】：本地 Git 浅克隆缓存策略（保留 2 小时，本地毫秒级全量扫描，最大分析 500 个文件）
        boolean planASuccess = false;
        if (StringUtils.isNotBlank(cloneUrl)) {
            try {
                log.info("【方案 A 启动】准备仓库 [{}] 分支 [{}] 本地 Git 浅克隆缓存 (保留 2 小时)...", actualRepoName, branch);
                File localRepoDir = yunxiaoGitCloneService.prepareLocalRepository(actualRepoId, actualRepoName, branch, cloneUrl);
                matchedSnippets = yunxiaoGitCloneService.scanLocalRepository(localRepoDir, searchKeyword, localMaxFiles);
                planASuccess = true;
                log.info("【方案 A 成功】本地全量扫描完成，命中核心代码片段数: {} (最大上限: {})", matchedSnippets.size(), localMaxFiles);
            } catch (Exception e) {
                log.warn("【方案 A 降级】本地 Git 浅克隆或扫描异常 ({})，回退至云效 OpenAPI 远程文件树扫描模式", e.getMessage());
            }
        }

        // 若方案 A 未成功执行，降级使用云效 OpenAPI 逐层扫描（远程最大分析 30 个文件）
        if (!planASuccess) {
            log.info("【降级方案执行】通过云效 OpenAPI 远程文件树检索代码 (最大分析文件数: {})...", remoteMaxFiles);
            List<YunxiaoCodeFileTreeDTO> allFiles = fetchRepositoryFileTree(actualRepoId, branch);
            List<YunxiaoCodeFileTreeDTO> candidateFiles = filterAndRankCandidates(allFiles, searchKeyword);
            matchedSnippets = extractMatchedSnippets(actualRepoId, branch, candidateFiles, searchKeyword, remoteMaxFiles);
        }

        if (CollectionUtils.isEmpty(matchedSnippets)) {
            String emptyReport = String.format("未能在此分支 [%s] 的代码中检索到与「%s」直接相关的代码片段。\n建议核对分支名称或提问关键词是否正确。",
                    branch, searchKeyword);
            return YunxiaoCodeScanResultDTO.builder()
                    .repositoryId(actualRepoId)
                    .repositoryName(actualRepoName)
                    .branch(branch)
                    .question(question)
                    .matchedFileCount(Constants.ZERO)
                    .matchedFiles(Collections.emptyList())
                    .analysisReport(emptyReport)
                    .build();
        }

        // 5. 组装专业的代码审计 Prompt 并调用 OpenCode 大模型
        List<OpenChatMessageDTO> messages = buildPromptMessages(actualRepoName, actualRepoId, branch, question, searchKeyword, matchedSnippets);
        log.info("准备调用 OpenCode 大模型进行分支代码梳理分析...");
        String analysisReport = openCodeService.chat(messages);

        List<String> matchedFilePaths = matchedSnippets.stream()
                .map(YunxiaoCodeSnippetDTO::getFilePath)
                .collect(Collectors.toList());

        return YunxiaoCodeScanResultDTO.builder()
                .repositoryId(actualRepoId)
                .repositoryName(actualRepoName)
                .branch(branch)
                .question(question)
                .matchedFileCount(matchedSnippets.size())
                .matchedFiles(matchedFilePaths)
                .analysisReport(analysisReport)
                .build();
    }

    /**
     * 解析钉钉群聊消息中的分支扫描指令并执行大模型分析
     *
     * @param chatInput 用户群聊输入文本
     * @return 格式化后的分析报告文本
     */
    @Override
    public String scanAndAnalyzeForChat(String chatInput) {
        if (StringUtils.isBlank(chatInput)) {
            return "❌ 请输入扫描指令，格式如：\n扫描项目 [项目名] 分支 [分支名] [提问诉求]\n例如：扫描项目 changtai-cloud 分支 dev 梳理实报使用的地方";
        }

        // 正则解析支持多种常见提问格式：
        // 1. 扫描项目 changtai-cloud 分支 dev 梳理实报使用的地方
        // 2. 扫描分支 changtai-cloud dev 梳理实报
        String repoName = null;
        String branch = null;
        String question = null;

        Pattern p1 = Pattern.compile("(?:扫描|分析)(?:项目|仓库)?\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.]+)\\s*(?:的分支|分支)?\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.\\/]+)\\s*[,，\\s]+(.*)");
        Matcher m1 = p1.matcher(chatInput.trim());
        if (m1.find()) {
            repoName = m1.group(Constants.ONE);
            branch = m1.group(Constants.TWO);
            question = m1.group(Constants.THREE);
        } else {
            // 空格拆分解析：如 "扫描 changtai-cloud dev 梳理实报使用的地方"
            String clean = chatInput.replaceAll("^(扫描|代码扫描|分析代码|分支扫描)+", "").trim();
            String[] parts = clean.split("\\s+");
            if (parts.length >= Constants.THREE) {
                repoName = parts[Constants.ZERO];
                branch = parts[Constants.ONE];
                // 剩余全部作为 question
                question = clean.substring(clean.indexOf(branch) + branch.length()).trim();
            }
        }

        if (StringUtils.isBlank(repoName) || StringUtils.isBlank(branch) || StringUtils.isBlank(question)) {
            return "❌ 无法解析指令参数，请按照以下格式输入：\n"
                    + "👉 **格式**：扫描项目 [项目名] 分支 [分支名] [具体诉求]\n"
                    + "👉 **示例**：`扫描项目 changtai-cloud 分支 dev 梳理实报使用的地方`";
        }

        try {
            YunxiaoCodeScanParam param = YunxiaoCodeScanParam.builder()
                    .repositoryId(repoName)
                    .branch(branch)
                    .question(question)
                    .build();

            YunxiaoCodeScanResultDTO result = scanAndAnalyzeBranch(param);
            if (result == null || StringUtils.isBlank(result.getAnalysisReport())) {
                return "❌ 未获取到分析结果";
            }
            return result.getAnalysisReport().trim();
        } catch (Exception e) {
            log.error("分支代码扫描大模型分析处理异常: {}", e.getMessage(), e);
            return "❌ 分支代码扫描分析失败: " + e.getMessage();
        }
    }

    /**
     * 解析并匹配代码库
     *
     * @param repoIdOrName 代码库ID或名称
     * @return 代码库实体
     */
    private YunxiaoRepositoryDTO resolveRepository(String repoIdOrName) {
        // 如果是纯数字，按 ID 匹配
        if (StringUtils.isNumeric(repoIdOrName)) {
            YunxiaoRepositoryQueryParam queryParam = new YunxiaoRepositoryQueryParam();
            queryParam.setPage(Constants.ONE_LONG);
            queryParam.setPerPage(Constants.HUNDRED_LONG);
            List<YunxiaoRepositoryDTO> repos = yunxiaoDevopsService.listRepositories(queryParam);
            for (YunxiaoRepositoryDTO repo : repos) {
                if (repo.getId() != null && String.valueOf(repo.getId()).equals(repoIdOrName)) {
                    return repo;
                }
            }
            return YunxiaoRepositoryDTO.builder().id(Long.parseLong(repoIdOrName)).name(repoIdOrName).build();
        }

        // 否则按名称或路径检索匹配
        YunxiaoRepositoryQueryParam queryParam = new YunxiaoRepositoryQueryParam();
        queryParam.setSearch(repoIdOrName);
        queryParam.setPage(Constants.ONE_LONG);
        queryParam.setPerPage(Constants.TWENTY_LONG);

        List<YunxiaoRepositoryDTO> repos = yunxiaoDevopsService.listRepositories(queryParam);
        if (CollectionUtils.isEmpty(repos)) {
            throw new IllegalArgumentException("未找到匹配的代码库: " + repoIdOrName);
        }

        // 精确匹配
        for (YunxiaoRepositoryDTO repo : repos) {
            if (StringUtils.equalsIgnoreCase(repo.getName(), repoIdOrName)
                    || StringUtils.equalsIgnoreCase(repo.getPath(), repoIdOrName)) {
                return repo;
            }
        }

        // 默认返回第一个匹配结果
        return repos.get(Constants.ZERO);
    }

    /**
     * 解析关键词
     *
     * @param explicitKeyword 显式指定的关键词
     * @param question        问题诉求
     * @return 最终关键词
     */
    private String resolveKeyword(String explicitKeyword, String question) {
        if (StringUtils.isNotBlank(explicitKeyword)) {
            return explicitKeyword.trim();
        }
        // 尝试从常见中文问题中提取关键词（如“梳理实报使用的地方” -> “实报”）
        String cleaned = question.replaceAll("^(请|帮我|如何|怎样|梳理|查找|分析|查看|搜索|查询)+", "")
                .replaceAll("(使用的地方|相关的代码|在哪些地方用到了|的具体实现|的逻辑|使用情况)+$", "")
                .trim();
        return StringUtils.isNotBlank(cleaned) ? cleaned : question;
    }

    /**
     * 获取分支递归文件树
     *
     * @param repoId 代码库ID
     * @param branch 分支名称
     * @return 文件树节点列表
     */
    private List<YunxiaoCodeFileTreeDTO> fetchRepositoryFileTree(String repoId, String branch) {
        String url = getBaseEndpoint()
                + CODEUP_BASE_PATH
                + "/organizations/"
                + yunxiaoProperties.getOrganizationId()
                + "/repositories/"
                + repoId
                + "/files/tree?refName="
                + branch
                + "&type=RECURSIVE";

        HttpEntity<?> entity = createAuthEntity();
        try {
            ResponseEntity<String> response = yunxiaoRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String body = response.getBody();
            if (StringUtils.isBlank(body)) {
                return Collections.emptyList();
            }

            JSONArray array = JSON.parseArray(body.trim());
            if (CollectionUtils.isEmpty(array)) {
                return Collections.emptyList();
            }

            List<YunxiaoCodeFileTreeDTO> list = new ArrayList<>();
            for (int i = Constants.ZERO; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                list.add(YunxiaoCodeFileTreeDTO.builder()
                        .id(item.getString("id"))
                        .name(item.getString("name"))
                        .path(item.getString("path"))
                        .type(item.getString("type"))
                        .mode(item.getString("mode"))
                        .build());
            }
            return list;
        } catch (Exception e) {
            log.error("获取代码库分支文件树异常, repoId: {}, branch: {}, error: {}", repoId, branch, e.getMessage(), e);
            throw new RuntimeException("获取代码库文件树失败: " + e.getMessage(), e);
        }
    }

    /**
     * 筛选并给候选文件打分排序
     *
     * @param allFiles 全量文件树
     * @param keyword  搜索关键词
     * @return 排序后的候选文件列表
     */
    private List<YunxiaoCodeFileTreeDTO> filterAndRankCandidates(List<YunxiaoCodeFileTreeDTO> allFiles, String keyword) {
        // 生成关键词相关的衍生词（如“实报” -> "actual", "reimburse", "report" 等）
        Set<String> searchTokens = buildSearchTokens(keyword);

        List<YunxiaoCodeFileTreeDTO> codeFiles = allFiles.stream()
                .filter(f -> TYPE_BLOB.equalsIgnoreCase(f.getType()))
                .filter(f -> isAllowedExtension(f.getPath()))
                .collect(Collectors.toList());

        // 排序规则：路径直接命中关键词的排在最前，随后是核心模块 Controller/Service/Mapper 文件
        codeFiles.sort(Comparator.comparingInt((YunxiaoCodeFileTreeDTO f) -> {
            String lowerPath = f.getPath().toLowerCase();
            for (String token : searchTokens) {
                if (lowerPath.contains(token)) {
                    return Constants.ZERO; // 最高优先级
                }
            }
            if (lowerPath.contains("controller") || lowerPath.contains("service") || lowerPath.contains("mapper")) {
                return Constants.ONE; // 次高优先级
            }
            return Constants.TWO; // 普通优先级
        }));

        return codeFiles;
    }

    /**
     * 逐个拉取文件内容并匹配关键词，提取代码片段
     *
     * @param repoId         代码库ID
     * @param branch         分支名
     * @param candidateFiles 候选文件列表
     * @param keyword        关键词
     * @param maxFiles       最大提取文件数
     * @return 命中的代码片段列表
     */
    private List<YunxiaoCodeSnippetDTO> extractMatchedSnippets(String repoId, String branch,
                                                              List<YunxiaoCodeFileTreeDTO> candidateFiles,
                                                              String keyword, int maxFiles) {
        List<YunxiaoCodeSnippetDTO> snippets = new ArrayList<>();
        Set<String> searchTokens = buildSearchTokens(keyword);

        // 远程模式下为了兼顾扫描效率与完整性，探测候选深度
        int scanLimit = Math.min(candidateFiles.size(), Math.max(Constants.FIFTY, maxFiles * Constants.TWO));

        for (int i = Constants.ZERO; i < scanLimit && snippets.size() < maxFiles; i++) {
            YunxiaoCodeFileTreeDTO candidate = candidateFiles.get(i);
            String filePath = candidate.getPath();

            // 判断路径是否直接命中
            boolean pathMatched = false;
            for (String token : searchTokens) {
                if (filePath.toLowerCase().contains(token)) {
                    pathMatched = true;
                    break;
                }
            }

            // 拉取文件内容
            String sourceCode = fetchFileContent(repoId, branch, filePath);
            if (StringUtils.isBlank(sourceCode)) {
                continue;
            }

            // 内容是否命中
            boolean contentMatched = false;
            for (String token : searchTokens) {
                if (sourceCode.toLowerCase().contains(token) || sourceCode.contains(keyword)) {
                    contentMatched = true;
                    break;
                }
            }

            if (pathMatched || contentMatched) {
                String snippetContent = truncateOrExtractSnippet(sourceCode, keyword, searchTokens);
                snippets.add(YunxiaoCodeSnippetDTO.builder()
                        .filePath(filePath)
                        .snippet(snippetContent)
                        .matchReason(pathMatched ? MATCH_REASON_PATH : MATCH_REASON_CONTENT)
                        .build());
            }
        }

        return snippets;
    }

    /**
     * 拉取单个文件源码内容并 Base64 解码
     *
     * @param repoId   代码库ID
     * @param branch   分支名
     * @param filePath 文件路径
     * @return 源码字符串
     */
    private String fetchFileContent(String repoId, String branch, String filePath) {
        try {
            // 对路径进行安全编码
            String encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.name())
                    .replaceAll("\\+", "%20");

            String urlStr = getBaseEndpoint()
                    + CODEUP_BASE_PATH
                    + "/organizations/"
                    + yunxiaoProperties.getOrganizationId()
                    + "/repositories/"
                    + repoId
                    + "/files/"
                    + encodedPath
                    + "?ref="
                    + branch;

            // 使用 URI.create 防止 RestTemplate 二次转义
            URI uri = URI.create(urlStr);
            HttpEntity<?> entity = createAuthEntity();

            ResponseEntity<String> response = yunxiaoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String body = response.getBody();
            if (StringUtils.isBlank(body)) {
                return null;
            }

            JSONObject blob = JSON.parseObject(body.trim());
            if (blob != null && blob.containsKey("content")) {
                String base64Content = blob.getString("content");
                byte[] decoded = Base64.getDecoder().decode(base64Content);
                return new String(decoded, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("拉取文件内容失败, filePath: {}, error: {}", filePath, e.getMessage());
        }
        return null;
    }

    /**
     * 截取或提取代码上下文片段（控制单文件 Token 长度）
     *
     * @param sourceCode   完整源码
     * @param keyword      关键词
     * @param searchTokens 搜索 Token 集合
     * @return 截取后的片段
     */
    private String truncateOrExtractSnippet(String sourceCode, String keyword, Set<String> searchTokens) {
        // 如果代码较短，直接保留完整文件
        if (sourceCode.length() <= Constants.FOUR_THOUSAND) {
            return sourceCode;
        }

        // 若代码较长，提取包含关键词的上下行片段
        String[] lines = sourceCode.split("\r?\n");
        List<String> matchedContextLines = new ArrayList<>();
        Set<Integer> targetLineIndexes = new HashSet<>();

        for (int i = Constants.ZERO; i < lines.length; i++) {
            String line = lines[i];
            for (String token : searchTokens) {
                if (line.toLowerCase().contains(token) || line.contains(keyword)) {
                    // 取命中行前后各 20 行
                    int start = Math.max(Constants.ZERO, i - Constants.TWENTY);
                    int end = Math.min(lines.length - Constants.ONE, i + Constants.TWENTY);
                    for (int k = start; k <= end; k++) {
                        targetLineIndexes.add(k);
                    }
                    break;
                }
            }
        }

        if (CollectionUtils.isEmpty(targetLineIndexes)) {
            // 没有行级命中时，取前 100 行
            int limit = Math.min(lines.length, Constants.HUNDRED);
            return String.join("\n", Arrays.copyOfRange(lines, Constants.ZERO, limit));
        }

        List<Integer> sortedIndexes = new ArrayList<>(targetLineIndexes);
        Collections.sort(sortedIndexes);

        int prev = -Constants.ONE;
        for (Integer idx : sortedIndexes) {
            if (prev != -Constants.ONE && idx > prev + Constants.ONE) {
                matchedContextLines.add("// ... [此处省略无关代码] ...");
            }
            matchedContextLines.add(lines[idx]);
            prev = idx;
        }

        return String.join("\n", matchedContextLines);
    }

    /**
     * 组装针对代码审计的大模型 Prompt 消息列表
     *
     * @param repoName        项目名
     * @param repoId          项目ID
     * @param branch          分支名
     * @param question        用户提问
     * @param searchKeyword   搜索关键词
     * @param matchedSnippets 命中的代码片段
     * @return Prompt 消息列表
     */
    private List<OpenChatMessageDTO> buildPromptMessages(String repoName, String repoId, String branch,
                                                         String question, String searchKeyword,
                                                         List<YunxiaoCodeSnippetDTO> matchedSnippets) {
        List<OpenChatMessageDTO> messages = new ArrayList<>();

        // System 角色设定：直给型资深架构师，杜绝客套套话与冗余概念
        String systemContent = "你是一名直接参与企业级业务研发的资深 Java 核心开发专家。"
                + "请抛弃任何客套话、宏观业务定义和模板化套话，直接给出高密度、最贴近实际工程代码的精准梳理。"
                + "从接口入口到核心实现层层拆解，必须标明具体的代码类名、方法名、代码行号区间、字段名、判断条件及边界处理。";
        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_SYSTEM)
                .content(systemContent)
                .build());

        // User 内容组装
        StringBuilder sb = new StringBuilder();
        sb.append("【代码库分支背景】\n");
        sb.append("- 代码库: ").append(repoName).append(" (ID: ").append(repoId).append(")\n");
        sb.append("- 扫描分支: ").append(branch).append("\n");
        sb.append("- 检索关键词: ").append(searchKeyword).append("\n");
        sb.append("- 分析诉求: ").append(question).append("\n\n");

        sb.append("【命中源码上下文（共 ").append(matchedSnippets.size()).append(" 个代码文件，源码带行号）】\n\n");

        // 限制放入 Prompt 的详细代码块数量（最多前 20 个高优先级文件展示完整核心代码切片，其余列出文件路径清单）
        int detailedLimit = Math.min(matchedSnippets.size(), Constants.TWENTY);
        for (int i = Constants.ZERO; i < detailedLimit; i++) {
            YunxiaoCodeSnippetDTO snippet = matchedSnippets.get(i);
            sb.append("### 文件 ").append(i + Constants.ONE).append(": `").append(snippet.getFilePath()).append("`\n");
            sb.append("```java\n");
            sb.append(snippet.getSnippet());
            sb.append("\n```\n\n");
        }

        if (matchedSnippets.size() > detailedLimit) {
            sb.append("### 其余命中关键词的代码文件清单（共 ").append(matchedSnippets.size() - detailedLimit).append(" 个文件）：\n");
            for (int i = detailedLimit; i < matchedSnippets.size(); i++) {
                sb.append("- `").append(matchedSnippets.get(i).getFilePath()).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("【输出格式硬性要求与参考样例】\n");
        sb.append("请严格遵循以下高密度输出结构，不要输出任何引言、客套话或模板套话：\n\n");
        sb.append("=== 格式参考示范 ===\n");
        sb.append("实报费用新增入口是 ScmActualReimbursementController.addReimbursement（POST /add，@Validated），核心实现见 ScmActualReimbursementServiceImpl.java:399-484。校验分四层：\n\n");
        sb.append("1. DTO 注解校验\n");
        sb.append("- 明细集合 details 不能为空\n");
        sb.append("- 备注 ≤200、箱号/提单号 ≤200、特殊费用单号 ≤255、附件 ≤10\n");
        sb.append("- 金额范围 -999999.99 ~ 999999.99\n\n");
        sb.append("2. 前置校验 preCheckReimbursement（ScmActualReimbursementServiceImpl.java:263）\n");
        sb.append("- 非草稿（izDraft=false）：\n");
        sb.append("  - 物流商必填且必须存在、启用\n");
        sb.append("  - 明细至少一条\n");
        sb.append("  - 每条明细：费用项名称、标准费用项（必须存在且停用校验，feeType=实报）、是否供应商责任、费用项类型、金额、币种必填\n");
        sb.append("  - 非供应商责任时金额不能小于 0.01\n");
        sb.append("  - 供应商责任=是：责任供应商编码+类型必填，且供应商有效；类型为 PURCHASE 时采购方必填\n");
        sb.append("  - 供应商责任=否：不能填责任供应商\n");
        sb.append("  - 特殊费用项：特殊费用单号必填，且「特殊费用单号+二级费用项」在本请求内不重复，是否供应商责任必须为否\n");
        sb.append("  - 非特殊费用项：不能填特殊费用单号，且「箱号+提单号+二级费用项」在本请求内唯一\n");
        sb.append("- 草稿：跳过以上全部明细及表头校验（但 DTO 的 details 非空仍生效，物流商可空）\n\n");
        sb.append("3. 数据库重复校验 checkDatabaseDuplicatesBatch（ScmActualReimbursementServiceImpl.java:1038，仅非草稿）\n");
        sb.append("- 特殊费用项跳过\n");
        sb.append("- 供应商责任=是：按「物流商+责任供应商+箱号+提单号+二级费用项」查重\n");
        sb.append("- 供应商责任=否：按「箱号+提单号+二级费用项」查是否已存在供应商责任=是 的记录（已有供应商承担则不允许再以非供应商责任提交）\n");
        sb.append("- SQL（ScmActualReimbursementDetailMapper.xml:51）：只排除已作废（status=5），已删除不算\n\n");
        sb.append("4. 并发与提交\n");
        sb.append("- 非草稿用 Redis 全局锁 lock:scm:actualReimbursement 防并发\n");
        sb.append("- 提交后走钉钉审批；审批开关关闭则直接置为已生效并生成采购应付单\n\n");
        sb.append("可留意的点\n");
        sb.append("- 草稿仍强制 details 非空，无法保存空草稿。\n");
        sb.append("- 新增不校验币种是否在字典中（仅导入校验），只要求非空。\n");
        sb.append("- DB 查重 SQL 只排除作废，待提交/已驳回的历史单据也会拦截新增。\n");
        sb.append("- checkDatabaseDuplicatesBatch 在 try 内，异常会被包装成「实报新增异常：」前缀。\n");
        sb.append("- 金额错误文案写的是「预计费用」，实为实报金额。\n");
        sb.append("=== 格式示范结束 ===\n\n");
        sb.append("【本次梳理要求】\n");
        sb.append("1. **第一句直奔主题**：指明入口方法（如 {Controller}.{method}（{HTTP Method} {Path}，{注解}）），核心实现见 {ServiceImpl}.java:{lineStart}-{lineEnd}。并说明流程或逻辑分几层/几步。\n");
        sb.append("2. **结构化分层拆解**：根据源码执行流依次分点阐述（如 DTO 注解校验、前置校验、数据库查重、事务/并发/状态流转等），必须精确写出具体的方法名、文件行号、字段名、判定条件、状态值、错误提示、关联 SQL/Redis 条件，禁止泛化套话。\n");
        sb.append("3. **可留意的点**：最后精炼列出边界特例、潜在坑点、代码设计不一致或缺陷等。\n");
        sb.append("4. **直接输出上述内容，严禁任何寒暄、问候、总结前言或后记**。\n");

        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(sb.toString())
                .build());

        return messages;
    }

    /**
     * 构建关联的搜索 Token 词表
     *
     * @param keyword 原始关键词
     * @return 衍生搜索词集合
     */
    private Set<String> buildSearchTokens(String keyword) {
        Set<String> tokens = new HashSet<>();
        tokens.add(keyword.toLowerCase());

        // 针对常见业务词“实报”扩展关联词
        if (keyword.contains("实报") || keyword.contains("报销")) {
            tokens.add("actual");
            tokens.add("actualreport");
            tokens.add("actualreimburse");
            tokens.add("actualreimbursement");
            tokens.add("reimbursement");
            tokens.add("reimburse");
            tokens.add("shibao");
        }
        return tokens;
    }

    /**
     * 校验文件扩展名是否合法
     *
     * @param path 文件路径
     * @return 是否允许扫描
     */
    private boolean isAllowedExtension(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取基础接入域名
     *
     * @return 基础 URL
     */
    private String getBaseEndpoint() {
        String endpoint = yunxiaoProperties.getEndpoint();
        if (StringUtils.isBlank(endpoint)) {
            endpoint = "openapi-rdc.aliyuncs.com";
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }
        return endpoint;
    }

    /**
     * 构建带认证 Token 的 HTTP 实体
     *
     * @return HttpEntity
     */
    private HttpEntity<?> createAuthEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(YUNXIAO_TOKEN_HEADER, yunxiaoProperties.getAccessToken().trim());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(headers);
    }
}
