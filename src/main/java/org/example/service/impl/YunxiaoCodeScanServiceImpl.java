package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.CodeAgentAnalysisDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.YunxiaoCodeFileBlobDTO;
import org.example.domain.dto.YunxiaoCodeFileTreeDTO;
import org.example.domain.dto.YunxiaoCodeScanInstructionDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.CodeSearchKeywordService;
import org.example.service.OpenCodeService;
import org.example.service.YunxiaoAgentToolService;
import org.example.service.YunxiaoCodeAgentService;
import org.example.service.YunxiaoCodeScanService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.example.util.CodeCitationVerifier;
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

    /**
     * 本地模式默认 / 最大分析文件数
     */
    private static final int LOCAL_MAX_FILES_DEFAULT = Constants.FIFTY;
    private static final int LOCAL_MAX_FILES_LIMIT = Constants.HUNDRED;

    /**
     * Prompt 中展示完整代码片段的总字符预算，超出预算的文件只列出路径
     */
    private static final int PROMPT_SNIPPET_CHAR_BUDGET = Constants.HUNDRED * Constants.THOUSAND;

    /**
     * Prompt 中展示完整代码片段的文件数下限，保证即使单文件较大也有足够的代码上下文
     */
    private static final int PROMPT_MIN_DETAILED_FILES = Constants.FIVE;

    /**
     * 表结构类问题中代码片段的字符预算（表名证据已由表引用汇总提供，代码仅作补充）
     */
    private static final int TABLE_QUESTION_SNIPPET_CHAR_BUDGET = Constants.TWENTY * Constants.THOUSAND;

    /**
     * 降级提示中错误原因的最大长度
     */
    private static final int MAX_DEGRADE_REASON_CHARS = Constants.TWO_HUNDRED;

    /**
     * 普通代码扫描最多大模型调用轮数（前 2 轮可调用工具补充源码，第 3 轮强制输出结论）
     */
    private static final int QUICK_SCAN_MAX_TURNS = Constants.THREE;

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

    @Resource
    private CodeSearchKeywordService codeSearchKeywordService;

    @Resource
    private YunxiaoAgentToolService yunxiaoAgentToolService;

    @Resource
    private YunxiaoCodeAgentService yunxiaoCodeAgentService;

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

        // 确定关键词与最大扫描文件数：本地分析默认 50、最大 100，远程分析最大 30
        String searchKeyword = codeSearchKeywordService.resolveCoreKeyword(param.getKeyword(), question);
        int localMaxFiles = param.getMaxFiles() != null && param.getMaxFiles() > Constants.ZERO
                ? Math.min(param.getMaxFiles(), LOCAL_MAX_FILES_LIMIT)
                : LOCAL_MAX_FILES_DEFAULT;
        int remoteMaxFiles = param.getMaxFiles() != null && param.getMaxFiles() > Constants.ZERO
                ? Math.min(param.getMaxFiles(), Constants.THIRTY)
                : Constants.THIRTY;

        log.info("开始扫描云效分支代码: 仓库 [{} ({})], 分支 [{}], 关键词 [{}], 诉求 [{}], 本地最大分析文件数: {}, 远程最大分析文件数: {}",
                actualRepoName, actualRepoId, branch, searchKeyword, question, localMaxFiles, remoteMaxFiles);

        List<YunxiaoCodeSnippetDTO> matchedSnippets = new ArrayList<>();
        // 表结构类问题：额外汇总「表名 → 实体 → 引用位置」作为一手证据，并使用简洁输出格式
        boolean tableQuestion = isTableQuestion(question);
        String tableSummary = null;
        String cloneUrl =StringUtils.isNotBlank(targetRepo.getHttpUrlToRepo())
                ? targetRepo.getHttpUrlToRepo()
                : (StringUtils.isNotBlank(targetRepo.getWebUrl()) ? targetRepo.getWebUrl() + ".git" : "");

        // 优先采用【方案 A】：本地 Git 浅克隆缓存策略（保留 2 小时，本地毫秒级全量扫描，默认分析 50 个文件、最大 100 个）
        boolean planASuccess = false;
        File localRepoDir = null;
        boolean deepScan = Boolean.TRUE.equals(param.getDeepScan());
        // 深度扫描降级为普通扫描时，在报告开头告知用户原因
        String degradeNotice = null;
        if (StringUtils.isNotBlank(cloneUrl)) {
            try {
                log.info("【方案 A 启动】准备仓库 [{}] 分支 [{}] 本地 Git 浅克隆缓存 (保留 2 小时)...", actualRepoName, branch);
                localRepoDir = yunxiaoGitCloneService.prepareLocalRepository(actualRepoId, actualRepoName, branch, cloneUrl);
            } catch (Exception e) {
                log.warn("【方案 A 降级】本地 Git 浅克隆异常 ({})，回退至云效 OpenAPI 远程文件树扫描模式", e.getMessage());
                if (deepScan) {
                    degradeNotice = "⚠️ 深度扫描需要本地克隆分支代码，本次克隆失败（" + abbreviateReason(e)
                            + "），已降级为普通扫描（云效远程检索），结果可能不完整。";
                }
            }
        } else if (deepScan) {
            degradeNotice = "⚠️ 代码库未返回克隆地址，深度扫描无法执行，已降级为普通扫描（云效远程检索），结果可能不完整。";
        }

        // 若开启多轮 Agent 深度代码探查模式，转交 YunxiaoCodeAgentService 执行 Tool Calling；失败时降级为本地普通扫描
        if (localRepoDir != null && deepScan) {
            try {
                log.info("【多轮 Agent 深度扫描】启用 Tool Calling 闭环探查模式...");
                return yunxiaoCodeAgentService.executeDeepScan(param, localRepoDir, actualRepoName, actualRepoId);
            } catch (Exception e) {
                log.warn("【深度扫描降级】Agent 探查失败 ({})，回退至本地普通扫描", e.getMessage(), e);
                degradeNotice = "⚠️ 深度扫描未完成（" + abbreviateReason(e) + "），已降级为普通扫描，结果可能不完整。";
            }
        }

        if (localRepoDir != null) {
            try {
                matchedSnippets = yunxiaoGitCloneService.scanLocalRepository(localRepoDir, searchKeyword, question, localMaxFiles);
                if (tableQuestion) {
                    tableSummary = buildTableSummary(localRepoDir, searchKeyword);
                }
                planASuccess = true;
                log.info("【方案 A 成功】本地全量扫描完成，命中核心代码片段数: {} (最大上限: {})", matchedSnippets.size(), localMaxFiles);
            } catch (Exception e) {
                log.warn("【方案 A 降级】本地扫描异常 ({})，回退至云效 OpenAPI 远程文件树扫描模式", e.getMessage());
            }
        }

        // 若方案 A 未成功执行，降级使用云效 OpenAPI 逐层扫描（远程最大分析 30 个文件）
        if (!planASuccess) {
            log.info("【降级方案执行】通过云效 OpenAPI 远程文件树检索代码 (最大分析文件数: {})...", remoteMaxFiles);
            List<YunxiaoCodeFileTreeDTO> allFiles = fetchRepositoryFileTree(actualRepoId, branch);
            List<YunxiaoCodeFileTreeDTO> candidateFiles = filterAndRankCandidates(allFiles, searchKeyword);
            matchedSnippets = extractMatchedSnippets(actualRepoId, branch, candidateFiles, searchKeyword, remoteMaxFiles);
        }

        if (CollectionUtils.isEmpty(matchedSnippets) && StringUtils.isBlank(tableSummary)) {
            String emptyReport = String.format("未能在此分支 [%s] 的代码中检索到与「%s」直接相关的代码片段。\n建议核对分支名称或提问关键词是否正确。",
                    branch, searchKeyword);
            return YunxiaoCodeScanResultDTO.builder()
                    .repositoryId(actualRepoId)
                    .repositoryName(actualRepoName)
                    .branch(branch)
                    .question(question)
                    .matchedFileCount(Constants.ZERO)
                    .matchedFiles(Collections.emptyList())
                    .analysisReport(prependNotice(degradeNotice, emptyReport))
                    .build();
        }

        // 5. 组装专业的代码审计 Prompt 并调用 OpenCode 大模型
        List<OpenChatMessageDTO> messages = tableQuestion
                ? buildTablePromptMessages(actualRepoName, branch, question, searchKeyword, tableSummary, matchedSnippets)
                : buildPromptMessages(actualRepoName, actualRepoId, branch, question, searchKeyword, matchedSnippets);
        List<String> matchedFilePaths = matchedSnippets.stream()
                .map(YunxiaoCodeSnippetDTO::getFilePath)
                .collect(Collectors.toCollection(ArrayList::new));
        String analysisReport = null;
        if (planASuccess) {
            // 本地克隆可用：以预检索片段为首轮上下文，允许大模型用工具补充源码，最多 QUICK_SCAN_MAX_TURNS 轮
            log.info("准备调用 OpenCode 大模型进行分支代码梳理分析（最多 {} 轮工具调用）...", QUICK_SCAN_MAX_TURNS);
            CodeAgentAnalysisDTO agentResult = yunxiaoCodeAgentService.analyzeWithTools(
                    appendToolUsageHint(messages), localRepoDir, QUICK_SCAN_MAX_TURNS);
            if (agentResult != null && StringUtils.isNotBlank(agentResult.getReport())) {
                analysisReport = agentResult.getReport();
                agentResult.getReadFiles().stream()
                        .filter(path -> !matchedFilePaths.contains(path))
                        .forEach(matchedFilePaths::add);
            }
        }
        if (analysisReport == null) {
            log.info("准备调用 OpenCode 大模型进行分支代码梳理分析...");
            analysisReport = openCodeService.chat(messages);
        }
        if (planASuccess) {
            analysisReport = CodeCitationVerifier.appendWarnings(analysisReport, localRepoDir, matchedFilePaths);
        }
        analysisReport = prependNotice(degradeNotice, analysisReport);

        return YunxiaoCodeScanResultDTO.builder()
                .repositoryId(actualRepoId)
                .repositoryName(actualRepoName)
                .branch(branch)
                .question(question)
                .matchedFileCount(matchedFilePaths.size())
                .matchedFiles(matchedFilePaths)
                .analysisReport(analysisReport)
                .build();
    }

    /**
     * 解析钉钉群聊消息中的分支扫描指令并执行大模型分析
     * 支持不指定项目名（默认 changtai-cloud）与不指定分支名（默认 dev）
     *
     * @param chatInput 用户群聊输入文本
     * @return 格式化后的分析报告文本
     */
    @Override
    public String scanAndAnalyzeForChat(String chatInput) {
        if (StringUtils.isBlank(chatInput)) {
            return buildHelpTip();
        }

        // 解析群聊指令参数
        YunxiaoCodeScanInstructionDTO instruction = parseInstruction(chatInput);
        if (instruction == null || StringUtils.isBlank(instruction.getQuestion())) {
            return buildHelpTip();
        }

        String repoName = instruction.getRepoName();
        String branch = instruction.getBranch();
        String question = instruction.getQuestion();
        boolean isDeep = chatInput.startsWith("深度扫描")
                || chatInput.startsWith("深度代码扫描")
                || chatInput.startsWith("代码深度扫描")
                || chatInput.contains("--deep");

        log.info("群聊指令解析成功: 目标项目 [{}], 目标分支 [{}], 深度探查模式: [{}], 诉求 [{}]",
                repoName, branch, isDeep, question);

        try {
            YunxiaoCodeScanParam param = YunxiaoCodeScanParam.builder()
                    .repositoryId(repoName)
                    .branch(branch)
                    .question(question)
                    .deepScan(isDeep)
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
     * 构建扫描指令格式提示说明
     *
     * @return 提示字符串
     */
    private String buildHelpTip() {
        return "❌ 请输入具体代码扫描诉求，例如：\n"
                + "👉 **默认项目与分支**：`代码扫描 梳理实报费用新增的校验逻辑`（默认项目：changtai-cloud，默认分支：dev）\n"
                + "👉 **自定义分支**：`代码扫描 分支 dev 梳理实报费用新增的校验逻辑`\n"
                + "👉 **自定义项目与分支**：`代码扫描 项目 changtai-cloud 分支 dev 梳理实报费用新增的校验逻辑`";
    }

    /**
     * 解析钉钉群聊消息中的分支扫描指令
     * 支持不指定项目名（默认 changtai-cloud）与不指定分支名（默认 dev）
     *
     * @param chatInput 用户群聊输入文本
     * @return 解析后的指令对象，若无法提取诉求则返回 null
     */
    public YunxiaoCodeScanInstructionDTO parseInstruction(String chatInput) {
        if (StringUtils.isBlank(chatInput)) {
            return null;
        }

        // 1. 剔除前缀动作词
        String clean = chatInput.trim().replaceFirst("^(?:深度代码扫描|代码深度扫描|深度扫描|分支代码扫描|代码扫描|扫描代码|扫描分支|分支扫描|分析分支|分析代码|审计分支|扫描|分析)\\s*", "").trim();
        clean = clean.replaceFirst("^--deep\\s*", "").trim();
        if (StringUtils.isBlank(clean)) {
            return null;
        }

        String repoName = null;
        String branch = null;
        String question = clean;

        // 2. 尝试提取显式关键字：项目/仓库
        Pattern repoPattern = Pattern.compile("(?:项目|仓库)\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.]+)");
        Matcher repoMatcher = repoPattern.matcher(question);
        if (repoMatcher.find()) {
            repoName = repoMatcher.group(Constants.ONE);
            question = question.substring(Constants.ZERO, repoMatcher.start()) + " " + question.substring(repoMatcher.end());
            question = question.trim();
        }

        // 3. 尝试提取显式关键字：分支
        Pattern branchPattern = Pattern.compile("分支\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.\\/]+)");
        Matcher branchMatcher = branchPattern.matcher(question);
        if (branchMatcher.find()) {
            branch = branchMatcher.group(Constants.ONE);
            question = question.substring(Constants.ZERO, branchMatcher.start()) + " " + question.substring(branchMatcher.end());
            question = question.trim();
        }

        // 4. 清理残留的分隔符（如开头的标点或冒号）
        question = question.replaceAll("^[，,：:\\s]+", "").trim();

        // 5. 如果没有通过关键字提取到仓库或分支，尝试从空格分隔的 tokens 中提取纯英文参数
        if (StringUtils.isBlank(repoName) || StringUtils.isBlank(branch)) {
            String[] tokens = question.split("\\s+");
            if (tokens.length >= Constants.TWO) {
                // 如果当前 repoName 为空，且首个 token 不包含中文（符合仓库名特征）
                if (StringUtils.isBlank(repoName) && !containsChinese(tokens[Constants.ZERO])) {
                    repoName = tokens[Constants.ZERO];
                    int repoIdx = question.indexOf(repoName);
                    question = question.substring(repoIdx + repoName.length()).trim();

                    // 继续检查下一个 token 是否为分支名（若当前 branch 仍为空且下一个 token 不含中文）
                    String[] remainingTokens = question.split("\\s+");
                    if (StringUtils.isBlank(branch) && remainingTokens.length >= Constants.TWO && !containsChinese(remainingTokens[Constants.ZERO])) {
                        branch = remainingTokens[Constants.ZERO];
                        int branchIdx = question.indexOf(branch);
                        question = question.substring(branchIdx + branch.length()).trim();
                    }
                } else if (StringUtils.isBlank(branch) && !containsChinese(tokens[Constants.ZERO])) {
                    // 若已有 repoName，但 branch 为空，且首个 token 不含中文，则作为分支名
                    branch = tokens[Constants.ZERO];
                    int branchIdx = question.indexOf(branch);
                    question = question.substring(branchIdx + branch.length()).trim();
                }
            }
        }

        // 6. 兜底默认值：若未指定项目名则默认 changtai-cloud，未指定分支名则默认 dev
        if (StringUtils.isBlank(repoName)) {
            repoName = Constants.DEFAULT_REPO_NAME;
        }
        if (StringUtils.isBlank(branch)) {
            branch = Constants.DEFAULT_DEV_BRANCH;
        }

        // 清理最终的问题内容
        question = question.replaceAll("^[，,：:\\s]+", "").trim();
        if (StringUtils.isBlank(question)) {
            return null;
        }

        return YunxiaoCodeScanInstructionDTO.builder()
                .repoName(repoName)
                .branch(branch)
                .question(question)
                .build();
    }

    /**
     * 判断字符串中是否包含中文字符
     *
     * @param str 待检测字符串
     * @return 是否包含中文
     */
    private boolean containsChinese(String str) {
        if (StringUtils.isBlank(str)) {
            return false;
        }
        return str.codePoints().anyMatch(codepoint ->
                Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
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
        return codeSearchKeywordService.resolveCoreKeyword(explicitKeyword, question);
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
                + "聚焦 Service/Mapper 中的核心业务实现层层拆解，必须标明具体的代码类名、方法名、代码行号区间、字段名、判断条件及边界处理。"
                + "Controller 只作为触发入口一笔带过，不评论接口规范。";
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
        appendSnippets(sb, matchedSnippets, PROMPT_SNIPPET_CHAR_BUDGET);

        // 格式示范只给结构骨架，不放具体业务内容，避免大模型照抄示范中的字段、规则或行号
        sb.append("【输出格式硬性要求与结构骨架】\n");
        sb.append("请严格遵循以下高密度输出结构，不要输出任何引言、客套话或模板套话。骨架中 {} 为占位符，必须替换为上方源码中的真实内容：\n\n");
        sb.append("=== 结构骨架 ===\n");
        sb.append("{业务}{动作}入口是 {Controller}.{method}，核心实现见 {ServiceImpl}.java:{起始行}-{结束行}。{逻辑}分 N 步：\n\n");
        sb.append("1. {第一步名称}（{方法名}，{文件名}.java:{行号}）\n");
        sb.append("- {具体条件 / 字段 / 状态值 / 报错文案}\n");
        sb.append("- {分支一}：{该分支的规则}\n");
        sb.append("- {分支二}：{该分支的规则}\n\n");
        sb.append("2. {第二步名称}（{方法名}，{文件名}.java:{行号}）\n");
        sb.append("- {查询条件 / SQL 条件（{Mapper}.xml:{行号}）/ 落库表}\n\n");
        sb.append("可留意的点\n");
        sb.append("- {源码中能直接看出的业务特例或坑点}\n");
        sb.append("=== 骨架结束 ===\n\n");
        sb.append("【本次梳理要求】\n");
        sb.append("1. **第一句直奔主题**：说明核心逻辑集中在 {ServiceImpl}.{method}（{文件名}.java:{行号}），结果写入哪张表/用于什么，并说明分几步。\n");
        sb.append("2. **结构化分层拆解**：按业务执行流分点阐述（如 触发场景、读取的配置、分组维度、计算/分摊/匹配规则、聚合与落库、下游使用），"
                + "必须精确写出具体的方法名、文件行号、字段名、判定条件、状态值、报错条件、关联 SQL/Redis 条件，禁止泛化套话。"
                + "触发入口只用一行列出（含 Controller 接口与内部调用处），不展开 Controller 的注解、参数与返回值。\n");
        sb.append("3. **可留意的点**：只写业务逻辑上的特例与坑点，最多 3 条，没有就不写。"
                + "不要评论接口规范（GET/POST、权限注解、@Log、参数校验、返回值类型、方法命名拼写），除非问题明确问到。\n");
        sb.append("4. **直接输出上述内容，严禁任何寒暄、问候、总结前言或后记**。\n");
        sb.append("5. **篇幅与问题匹配**：若问题只是询问简单事实（如有哪些状态、字段、类型、枚举值），直接用列表或表格给出答案并附出处文件，"
                + "不必套用上述分层结构与「可留意的点」。\n");
        sb.append("6. **源码不足时**：若核心方法体不在上述源码中，用一句话说明缺少哪个类的哪个方法，不要用 Controller 细节或推测填充篇幅。\n");
        sb.append("7. **引用必须可核对**：代码位置统一写成「文件名.java:行号」或「文件名.xml:行号」，行号取自源码中的 L 前缀，"
                + "不得估算；源码中没有出现的类、方法、字段、表名一律不写。\n");

        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(sb.toString())
                .build());

        return messages;
    }

    /**
     * 组装表结构类问题的 Prompt：以表引用汇总为一手证据，要求简洁表格式输出
     *
     * @param repoName        项目名
     * @param branch          分支名
     * @param question        用户提问
     * @param searchKeyword   搜索关键词
     * @param tableSummary    表引用汇总（可能为空）
     * @param matchedSnippets 命中的代码片段
     * @return Prompt 消息列表
     */
    private List<OpenChatMessageDTO> buildTablePromptMessages(String repoName, String branch, String question,
                                                              String searchKeyword, String tableSummary,
                                                              List<YunxiaoCodeSnippetDTO> matchedSnippets) {
        List<OpenChatMessageDTO> messages = new ArrayList<>();
        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_SYSTEM)
                .content("你是熟悉该工程的资深 Java 开发。回答简洁直接：先给结论，再给表格，不展开代码实现。"
                        + "表名必须来自给出的源码证据（@TableName、SQL、BaseMapper 等），严禁编造或按类名臆测。")
                .build());

        StringBuilder sb = new StringBuilder();
        sb.append("【背景】代码库 ").append(repoName).append("，分支 ").append(branch)
                .append("，关键词「").append(searchKeyword).append("」\n");
        sb.append("【问题】").append(question).append("\n\n");

        if (StringUtils.isNotBlank(tableSummary)) {
            sb.append("【表引用汇总（一手证据，表名以此为准）】\n").append(tableSummary).append("\n");
        }
        if (CollectionUtils.isNotEmpty(matchedSnippets)) {
            sb.append("【相关源码（补充参考）】\n\n");
            appendSnippets(sb, matchedSnippets, TABLE_QUESTION_SNIPPET_CHAR_BUDGET);
        }

        sb.append("【输出要求】\n");
        sb.append("1. 第一行直接给结论：「{业务模块}有 N 张核心表：」。\n");
        sb.append("2. 用 Markdown 表格列出核心表，两列：表名 | 作用（一句话，20 字以内，可括注关键字段）。"
                + "核心表指表名或实体类直接属于该业务的表，主表在前、子表在后。\n");
        sb.append("3. 若证据显示该业务还会写入公共表（如应付单、附件、操作日志），表格后用一行「另外还会间接写到公共表：」列出，每张表括注一句原因；"
                + "供应商、公司、字典、配置等只读基础档案表不要列。\n");
        sb.append("4. 全文控制在 15 行以内。不要写代码实现、行号、调用链、Controller 接口和风险点，除非问题明确要求。\n");
        sb.append("5. 不要任何寒暄、前言或总结。\n\n");
        sb.append("示例：\n");
        sb.append("调拨单模块有 2 张核心表：\n\n");
        sb.append("| 表名 | 作用 |\n|---|---|\n");
        sb.append("| allocation_order | 调拨单主表（单号、调出/调入仓、状态） |\n");
        sb.append("| allocation_order_detail | 调拨明细（SKU、调拨数量） |\n\n");
        sb.append("另外还会间接写到公共表：inventory_statement（调拨出入库时记库存流水）。\n");

        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(sb.toString())
                .build());
        return messages;
    }

    /**
     * 按字符预算拼接代码片段：至少展示前 5 个文件，超出预算的文件只列出路径
     *
     * @param sb         目标文本
     * @param snippets   代码片段
     * @param charBudget 字符预算
     */
    private void appendSnippets(StringBuilder sb, List<YunxiaoCodeSnippetDTO> snippets, int charBudget) {
        int detailedLimit = Constants.ZERO;
        int usedChars = Constants.ZERO;
        for (YunxiaoCodeSnippetDTO snippet : snippets) {
            int snippetChars = StringUtils.length(snippet.getSnippet());
            if (detailedLimit >= PROMPT_MIN_DETAILED_FILES && usedChars + snippetChars > charBudget) {
                break;
            }
            usedChars += snippetChars;
            detailedLimit++;
        }
        for (int i = Constants.ZERO; i < detailedLimit; i++) {
            YunxiaoCodeSnippetDTO snippet = snippets.get(i);
            sb.append("### 文件 ").append(i + Constants.ONE).append(": `").append(snippet.getFilePath()).append("`\n");
            sb.append("```java\n");
            sb.append(snippet.getSnippet());
            sb.append("\n```\n\n");
        }

        if (snippets.size() > detailedLimit) {
            sb.append("### 其余命中关键词的代码文件清单（共 ").append(snippets.size() - detailedLimit).append(" 个文件）：\n");
            for (int i = detailedLimit; i < snippets.size(); i++) {
                sb.append("- `").append(snippets.get(i).getFilePath()).append("`\n");
            }
            sb.append("\n");
        }
    }

    /**
     * 判断是否为表结构类问题（如「实报费用有几张表」「调拨涉及哪些表」）
     *
     * @param question 用户提问
     * @return 是否为表结构类问题
     */
    private boolean isTableQuestion(String question) {
        return codeSearchKeywordService.isTableQuestion(question);
    }

    /**
     * 在最后一条用户消息末尾追加工具使用说明（返回新列表，不修改原消息，原消息仍用于一次性调用兜底）
     */
    private List<OpenChatMessageDTO> appendToolUsageHint(List<OpenChatMessageDTO> messages) {
        List<OpenChatMessageDTO> result = new ArrayList<>(messages);
        int last = result.size() - Constants.ONE;
        OpenChatMessageDTO lastMessage = result.get(last);
        result.set(last, OpenChatMessageDTO.builder()
                .role(lastMessage.getRole())
                .content(lastMessage.getContent() + "\n\n【可用工具】上方源码是预先检索的片段，可能不完整。"
                        + "若回答所需的方法体、被调用方法、Mapper SQL 或调用方不在片段中，可调用 read_method、list_methods、"
                        + "find_callers、get_mapper_sql 等工具补充读取，最多 " + (QUICK_SCAN_MAX_TURNS - Constants.ONE)
                        + " 轮，之后必须直接输出结论；片段已足够时请直接回答，不要调用工具。"
                        + "工具读取到的代码同样按「文件名.java:行号」引用。")
                .build());
        return result;
    }

    /**
     * 在报告开头追加降级提示
     */
    private String prependNotice(String notice, String report) {
        if (StringUtils.isBlank(notice)) {
            return report;
        }
        return StringUtils.isBlank(report) ? notice : notice + "\n\n" + report;
    }

    /**
     * 截取异常原因（取首行并限制长度，避免 git 输出等长文本刷屏）
     */
    private String abbreviateReason(Exception e) {
        String message = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        String firstLine = message.split("\\r?\\n", Constants.TWO)[Constants.ZERO].trim();
        return StringUtils.abbreviate(firstLine, MAX_DEGRADE_REASON_CHARS);
    }

    /**
     * 借助 find_table_usages 汇总业务相关表，作为表结构类问题的一手证据
     *
     * @param repoDir       本地仓库目录
     * @param searchKeyword 业务关键词
     * @return 表引用汇总文本，无英文检索词或检索失败时返回 null
     */
    private String buildTableSummary(File repoDir, String searchKeyword) {
        // 表名均为英文：优先选用带下划线的表名形态 Token（如 actual_reimbursement），其次取最长的英文 Token
        String tableKeyword = buildSearchTokens(searchKeyword).stream()
                .filter(token -> token.matches("[a-z][a-z0-9_]{2,}"))
                .max(Comparator.comparing((String token) -> token.contains("_")).thenComparingInt(String::length))
                .orElse(null);
        if (tableKeyword == null) {
            log.info("关键词 [{}] 未能映射到英文表名检索词，跳过表引用汇总", searchKeyword);
            return null;
        }
        try {
            String summary = yunxiaoAgentToolService.executeTool("find_table_usages",
                    JSON.toJSONString(Collections.singletonMap("keyword", tableKeyword)), repoDir);
            log.info("表结构类问题，按 [{}] 汇总表引用完成，长度: {}", tableKeyword, StringUtils.length(summary));
            return summary;
        } catch (Exception e) {
            log.warn("表引用汇总异常, keyword: {}, error: {}", tableKeyword, e.getMessage());
            return null;
        }
    }

    /**
     * 构建关联的搜索 Token 词表
     *
     * @param keyword 原始关键词
     * @return 衍生搜索词集合
     */
    private Set<String> buildSearchTokens(String keyword) {
        return codeSearchKeywordService.buildSearchTokens(keyword, keyword);
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
