package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepoCacheMetadata;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.CodeSearchKeywordService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 云效本地 Git 浅克隆与缓存管理实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoGitCloneServiceImpl implements YunxiaoGitCloneService {

    /**
     * 缓存元数据记录文件名
     */
    private static final String CACHE_META_FILE_NAME = "_cache_meta.json";

    /**
     * 允许扫描的代码文件后缀列表
     */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".java", ".xml", ".vue", ".js", ".ts", ".sql"
    );

    /**
     * 忽略的排除目录集合
     */
    private static final List<String> EXCLUDE_DIRS = Arrays.asList(
            ".git", "target", "node_modules", ".idea", ".mvn", "bin"
    );

    /**
     * 匹配原因：本地全量源码高亮命中
     */
    private static final String MATCH_REASON_LOCAL_SCAN = "本地全量代码扫描精准命中";

    @Resource
    private YunxiaoProperties yunxiaoProperties;

    @Resource
    private YunxiaoDevopsService yunxiaoDevopsService;

    @Resource
    private CodeSearchKeywordService codeSearchKeywordService;

    /**
     * 获取或准备本地克隆仓库目录（2 小时内直接复用缓存，过期或不存在时重新浅克隆）
     *
     * @param repoId   代码库 ID
     * @param repoName 代码库名称
     * @param branch   分支名称
     * @param cloneUrl 代码库克隆地址
     * @return 本地仓库根目录
     */
    @Override
    public File prepareLocalRepository(String repoId, String repoName, String branch, String cloneUrl) {
        String baseCachePath = StringUtils.isNotBlank(yunxiaoProperties.getCloneCacheDir())
                ? yunxiaoProperties.getCloneCacheDir()
                : "data/repo_cache";

        // 安全分支目录名（替换路径分隔符）
        String safeBranchName = branch.replaceAll("[\\\\/:]", "_");
        File baseDir = new File(baseCachePath);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File targetDir = new File(baseDir, repoName + "_" + safeBranchName);
        File metaFile = new File(targetDir, CACHE_META_FILE_NAME);
        File gitDir = new File(targetDir, ".git");

        long now = System.currentTimeMillis();
        long expireDuration = yunxiaoProperties.getCloneCacheExpireHours() != null
                ? yunxiaoProperties.getCloneCacheExpireHours() * 3600000L
                : Constants.TWO_HOURS_MILLIS;

        // 1. 检查本地是否存在有效缓存且未超过 2 小时
        if (targetDir.exists() && gitDir.exists() && metaFile.exists()) {
            try {
                String metaContent = Files.readString(metaFile.toPath(), StandardCharsets.UTF_8);
                YunxiaoRepoCacheMetadata meta = JSON.parseObject(metaContent, YunxiaoRepoCacheMetadata.class);
                if (meta != null && meta.getExpireAt() != null && now < meta.getExpireAt()) {
                    long remainingMinutes = (meta.getExpireAt() - now) / 60000L;
                    log.info("【本地克隆缓存命中】仓库 [{}] 分支 [{}] 在 2 小时有效期内（剩余约 {} 分钟），跳过重新拉取，直接复用: {}",
                            repoName, branch, remainingMinutes, targetDir.getAbsolutePath());
                    return targetDir;
                }
            } catch (Exception e) {
                log.warn("读取本地缓存元数据异常，将重新拉取: {}", e.getMessage());
            }
        }

        // 2. 缓存不存在或已过期，执行本地 Git 浅克隆
        log.info("【本地克隆开始】仓库 [{}] 分支 [{}] 缓存不存在或已超过 2 小时，开始执行 git clone --depth 1 ...", repoName, branch);

        if (targetDir.exists()) {
            deleteDirectoryRecursively(targetDir);
        }

        executeGitClone(cloneUrl, branch, targetDir);

        // 3. 记录缓存元数据（保存 2 小时）
        try {
            YunxiaoRepoCacheMetadata newMeta = YunxiaoRepoCacheMetadata.builder()
                    .repositoryId(repoId)
                    .repositoryName(repoName)
                    .branch(branch)
                    .cloneDir(targetDir.getAbsolutePath())
                    .cachedAt(now)
                    .expireAt(now + expireDuration)
                    .build();
            Files.writeString(metaFile.toPath(), JSON.toJSONString(newMeta), StandardCharsets.UTF_8);
            log.info("【本地克隆成功】仓库 [{}] 分支 [{}] 已克隆至本地，缓存已锁定 2 小时至: {}",
                    repoName, branch, new java.util.Date(now + expireDuration));
        } catch (Exception e) {
            log.warn("写入缓存元数据失败: {}", e.getMessage());
        }

        return targetDir;
    }

    /**
     * 在本地仓库中进行全量源码检索并提取高质量业务代码片段
     *
     * @param repoDir       本地仓库根目录
     * @param searchKeyword 搜索业务关键词
     * @param maxFiles      最大提取相关文件数
     * @return 命中的代码片段列表
     */
    @Override
    public List<YunxiaoCodeSnippetDTO> scanLocalRepository(File repoDir, String searchKeyword, int maxFiles) {
        if (repoDir == null || !repoDir.exists() || !repoDir.isDirectory()) {
            return Collections.emptyList();
        }

        Set<String> searchTokens = new HashSet<>(codeSearchKeywordService.buildSearchTokens(searchKeyword, searchKeyword));
        List<File> matchedFiles = new ArrayList<>();

        // 1. 毫秒级遍历本地全量文件树
        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<File> candidates = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isAllowedCodeFile)
                    .collect(Collectors.toList());

            log.info("本地工程源码文件检索完毕，待扫描源码文件总数: {}", candidates.size());

            // 2. 内存全文扫描匹配
            for (File file : candidates) {
                String relativePath = getRelativePath(repoDir, file);
                boolean pathHit = false;
                for (String token : searchTokens) {
                    if (relativePath.toLowerCase().contains(token)) {
                        pathHit = true;
                        break;
                    }
                }

                if (pathHit) {
                    matchedFiles.add(file);
                    continue;
                }

                // 扫描内容
                if (fileContainsTokens(file, searchTokens, searchKeyword)) {
                    matchedFiles.add(file);
                }
            }

            // 3. 若初次检索未命中任何文件，借助大模型语义推理推断英文 token 进行二次兜底检索
            if (matchedFiles.isEmpty()) {
                Set<String> aiTokens = codeSearchKeywordService.inferEnglishTokensWithAI(searchKeyword);
                if (CollectionUtils.isNotEmpty(aiTokens)) {
                    log.info("初次扫描未命中，补充大模型推断英文 Token 二次检索: {}", aiTokens);
                    searchTokens.addAll(aiTokens);
                    for (File file : candidates) {
                        String relativePath = getRelativePath(repoDir, file);
                        boolean pathHit = false;
                        for (String token : aiTokens) {
                            if (relativePath.toLowerCase().contains(token)) {
                                pathHit = true;
                                break;
                            }
                        }

                        if (pathHit) {
                            matchedFiles.add(file);
                            continue;
                        }

                        if (fileContainsTokens(file, aiTokens, searchKeyword)) {
                            matchedFiles.add(file);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("本地文件扫描异常: {}", e.getMessage(), e);
        }

        log.info("本地全量匹配完成，命中关键词 [{}] 的源码文件总数: {}", searchKeyword, matchedFiles.size());

        // 3. 排序：Controller > Service > Mapper > XML > Entity
        matchedFiles.sort(Comparator.comparingInt((File f) -> {
            String path = f.getAbsolutePath().toLowerCase();
            if (path.contains("controller")) {
                return Constants.ZERO;
            }
            if (path.contains("service")) {
                return Constants.ONE;
            }
            if (path.contains("mapper") || path.endsWith(".xml")) {
                return Constants.TWO;
            }
            return Constants.THREE;
        }));

        // 4. 提取代码片段
        List<YunxiaoCodeSnippetDTO> snippets = new ArrayList<>();
        int limit = Math.min(matchedFiles.size(), maxFiles);

        for (int i = Constants.ZERO; i < limit; i++) {
            File file = matchedFiles.get(i);
            String relativePath = getRelativePath(repoDir, file);
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String snippet = extractLocalSnippet(content, searchKeyword, searchTokens);

                snippets.add(YunxiaoCodeSnippetDTO.builder()
                        .filePath(relativePath)
                        .snippet(snippet)
                        .matchReason(MATCH_REASON_LOCAL_SCAN)
                        .build());
            } catch (Exception e) {
                log.warn("读取本地文件内容失败, file: {}, error: {}", relativePath, e.getMessage());
            }
        }

        return snippets;
    }

    /**
     * 递归删除目录
     *
     * @param dir 目录
     */
    private void deleteDirectoryRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            log.warn("递归删除目录异常, dir: {}, error: {}", dir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * 执行 Git 浅克隆命令
     *
     * @param cloneUrl  克隆地址
     * @param branch    分支名
     * @param targetDir 目标目录
     */
    private void executeGitClone(String cloneUrl, String branch, File targetDir) {
        // Windows 系统下必须显式开启 core.longpaths=true，防止长路径文件（如超长 Java 类名）签出失败
        List<String> command = Arrays.asList(
                "git", "clone", "-c", "core.longpaths=true", "--depth", "1", "-b", branch, cloneUrl, targetDir.getAbsolutePath()
        );

        log.info("正在执行 Git 命令: git clone -c core.longpaths=true --depth 1 -b {} {} {}", branch, cloneUrl, targetDir.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(Constants.SIXTY_THOUSAND, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Git 克隆超时 (超过 60 秒)");
            }

            int exitCode = process.exitValue();
            if (exitCode != Constants.ZERO) {
                log.error("Git 克隆失败, 退出码: {}, 输出: {}", exitCode, output);
                throw new RuntimeException("Git 浅克隆失败: " + output);
            }
        } catch (Exception e) {
            log.error("执行 Git 克隆进程异常: {}", e.getMessage(), e);
            throw new RuntimeException("执行本地 Git 浅克隆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断文件内容是否包含关键词
     *
     * @param file          文件
     * @param searchTokens  衍生 Token 集合
     * @param originKeyword 原始关键词
     * @return 是否包含
     */
    private boolean fileContainsTokens(File file, Set<String> searchTokens, String originKeyword) {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(originKeyword)) {
                    return true;
                }
                String lowerLine = line.toLowerCase();
                for (String token : searchTokens) {
                    if (lowerLine.contains(token)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 提取本地代码上下文片段
     *
     * @param content       完整代码
     * @param keyword       关键词
     * @param searchTokens  Token 集合
     * @return 截取后的代码片段
     */
    private String extractLocalSnippet(String content, String keyword, Set<String> searchTokens) {
        if (content.length() <= Constants.FOUR_THOUSAND) {
            return content;
        }

        String[] lines = content.split("\r?\n");
        Set<Integer> targetLineIndexes = new HashSet<>();

        for (int i = Constants.ZERO; i < lines.length; i++) {
            String line = lines[i];
            boolean hit = line.contains(keyword);
            if (!hit) {
                String lower = line.toLowerCase();
                for (String token : searchTokens) {
                    if (lower.contains(token)) {
                        hit = true;
                        break;
                    }
                }
            }

            if (hit) {
                // 取前后各 20 行
                int start = Math.max(Constants.ZERO, i - Constants.TWENTY);
                int end = Math.min(lines.length - Constants.ONE, i + Constants.TWENTY);
                for (int k = start; k <= end; k++) {
                    targetLineIndexes.add(k);
                }
            }
        }

        if (CollectionUtils.isEmpty(targetLineIndexes)) {
            int limit = Math.min(lines.length, Constants.HUNDRED);
            return String.join("\n", Arrays.copyOfRange(lines, Constants.ZERO, limit));
        }

        List<Integer> sorted = new ArrayList<>(targetLineIndexes);
        Collections.sort(sorted);

        List<String> resultLines = new ArrayList<>();
        int prev = -Constants.ONE;
        for (Integer idx : sorted) {
            if (prev != -Constants.ONE && idx > prev + Constants.ONE) {
                resultLines.add("// ... [此处省略无关行] ...");
            }
            // 附带行号，方便架构师级大模型精准分析定位
            resultLines.add(String.format("L%d: %s", idx + Constants.ONE, lines[idx]));
            prev = idx;
        }

        return String.join("\n", resultLines);
    }

    /**
     * 获取相对路径
     *
     * @param baseDir 基础目录
     * @param file    文件
     * @return 相对路径
     */
    private String getRelativePath(File baseDir, File file) {
        return baseDir.toPath().relativize(file.toPath()).toString().replace("\\", "/");
    }

    /**
     * 校验是否属于源码文件
     *
     * @param file 文件
     * @return 是否有效
     */
    private boolean isAllowedCodeFile(File file) {
        String path = file.getAbsolutePath().replace("\\", "/");
        for (String exclude : EXCLUDE_DIRS) {
            if (path.contains("/" + exclude + "/") || path.endsWith("/" + exclude)) {
                return false;
            }
        }
        String name = file.getName().toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
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
     * 强制重新克隆指定分支（先删除历史本地克隆，后重新浅克隆并锁定 2 小时缓存）
     *
     * @param repoId   代码库 ID
     * @param repoName 代码库名称
     * @param branch   分支名称（不指定默认 dev）
     * @param cloneUrl 代码库克隆地址
     * @return 本地仓库根目录
     */
    @Override
    public File forceRecloneRepository(String repoId, String repoName, String branch, String cloneUrl) {
        String effectiveBranch = StringUtils.isNotBlank(branch) ? branch.trim() : Constants.DEFAULT_DEV_BRANCH;

        String baseCachePath = StringUtils.isNotBlank(yunxiaoProperties.getCloneCacheDir())
                ? yunxiaoProperties.getCloneCacheDir()
                : "data/repo_cache";

        String safeBranchName = effectiveBranch.replaceAll("[\\\\/:]", "_");
        File baseDir = new File(baseCachePath);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File targetDir = new File(baseDir, repoName + "_" + safeBranchName);
        File metaFile = new File(targetDir, CACHE_META_FILE_NAME);

        // 1. 先删除历史的本地克隆缓存目录
        if (targetDir.exists()) {
            log.info("【克隆分支-先删历史】检测到存在旧版本缓存，正在清理: {}", targetDir.getAbsolutePath());
            deleteDirectoryRecursively(targetDir);
        }

        // 2. 后新增克隆
        log.info("【克隆分支-后新增】正在拉取仓库 [{}] 分支 [{}] 最新代码...", repoName, effectiveBranch);
        executeGitClone(cloneUrl, effectiveBranch, targetDir);

        // 3. 记录缓存元数据（锁定 2 小时）
        long now = System.currentTimeMillis();
        long expireDuration = yunxiaoProperties.getCloneCacheExpireHours() != null
                ? yunxiaoProperties.getCloneCacheExpireHours() * 3600000L
                : Constants.TWO_HOURS_MILLIS;

        try {
            YunxiaoRepoCacheMetadata newMeta = YunxiaoRepoCacheMetadata.builder()
                    .repositoryId(repoId)
                    .repositoryName(repoName)
                    .branch(effectiveBranch)
                    .cloneDir(targetDir.getAbsolutePath())
                    .cachedAt(now)
                    .expireAt(now + expireDuration)
                    .build();
            Files.writeString(metaFile.toPath(), JSON.toJSONString(newMeta), StandardCharsets.UTF_8);
            log.info("【克隆分支-完成】仓库 [{}] 分支 [{}] 克隆成功，缓存已锁定 2 小时至: {}",
                    repoName, effectiveBranch, new java.util.Date(now + expireDuration));
        } catch (Exception e) {
            log.warn("写入缓存元数据失败: {}", e.getMessage());
        }

        return targetDir;
    }

    /**
     * 解析钉钉群聊克隆分支指令，先删除历史克隆缓存，再拉取新增
     * 支持仓库名缺省（默认 changtai-cloud）与分支名缺省（默认 dev）
     *
     * @param userQuestion 用户提问内容
     * @return 执行结果信息
     */
    @Override
    public String cloneBranchForChat(String userQuestion) {
        // 去除指令前缀动作词
        String content = userQuestion.replaceAll("^(克隆分支|克隆项目|克隆代码|重新克隆|拉取分支)\\s*", "").trim();

        String repoIdOrName = null;
        String branch = null;
        boolean repoDefaulted = false;
        boolean branchDefaulted = false;

        if (StringUtils.isNotBlank(content)) {
            String working = content;

            // 1. 尝试显式匹配 "项目/仓库 xxx"
            Pattern repoPattern = Pattern.compile("(?:项目|仓库)\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.]+)");
            Matcher repoMatcher = repoPattern.matcher(working);
            if (repoMatcher.find()) {
                repoIdOrName = repoMatcher.group(Constants.ONE);
                working = working.substring(Constants.ZERO, repoMatcher.start()) + " " + working.substring(repoMatcher.end());
                working = working.trim();
            }

            // 2. 尝试显式匹配 "分支 xxx"
            Pattern branchPattern = Pattern.compile("分支\\s*[:：]?\\s*([a-zA-Z0-9_\\-\\.\\/]+)");
            Matcher branchMatcher = branchPattern.matcher(working);
            if (branchMatcher.find()) {
                branch = branchMatcher.group(Constants.ONE);
                working = working.substring(Constants.ZERO, branchMatcher.start()) + " " + working.substring(branchMatcher.end());
                working = working.trim();
            }

            // 3. 处理无显式关键字的纯参数
            String[] tokens = working.split("\\s+");
            List<String> cleanTokens = new ArrayList<>();
            for (String token : tokens) {
                if (StringUtils.isNotBlank(token)) {
                    cleanTokens.add(token.trim());
                }
            }

            if (!cleanTokens.isEmpty()) {
                if (StringUtils.isBlank(repoIdOrName) && StringUtils.isBlank(branch)) {
                    if (cleanTokens.size() == Constants.ONE) {
                        String singleToken = cleanTokens.get(Constants.ZERO);
                        // 若单个参数具有分支特征（如 dev、master、feature/* 等），则作为分支名；否则作为仓库名
                        if (isLikelyBranchName(singleToken)) {
                            branch = singleToken;
                        } else {
                            repoIdOrName = singleToken;
                        }
                    } else {
                        // 两个及以上参数，首个为仓库，第二个为分支
                        repoIdOrName = cleanTokens.get(Constants.ZERO);
                        branch = cleanTokens.get(Constants.ONE);
                    }
                } else if (StringUtils.isBlank(repoIdOrName)) {
                    repoIdOrName = cleanTokens.get(Constants.ZERO);
                } else if (StringUtils.isBlank(branch)) {
                    branch = cleanTokens.get(Constants.ZERO);
                }
            }
        }

        // 4. 缺省值兜底：仓库名默认 changtai-cloud，分支名默认 dev
        if (StringUtils.isBlank(repoIdOrName)) {
            repoIdOrName = Constants.DEFAULT_REPO_NAME;
            repoDefaulted = true;
        }
        if (StringUtils.isBlank(branch)) {
            branch = Constants.DEFAULT_DEV_BRANCH;
            branchDefaulted = true;
        }

        log.info("收到群聊克隆分支指令: 仓库 [{}] (默认={}), 分支 [{}] (默认={})",
                repoIdOrName, repoDefaulted, branch, branchDefaulted);

        try {
            // 解析匹配实际代码库
            YunxiaoRepositoryDTO targetRepo = resolveRepository(repoIdOrName);
            String actualRepoId = String.valueOf(targetRepo.getId());
            String actualRepoName = targetRepo.getName();

            String cloneUrl = StringUtils.isNotBlank(targetRepo.getHttpUrlToRepo())
                    ? targetRepo.getHttpUrlToRepo()
                    : (StringUtils.isNotBlank(targetRepo.getWebUrl()) ? targetRepo.getWebUrl() + ".git" : "");

            if (StringUtils.isBlank(cloneUrl)) {
                return "❌ 未获取到代码库 [" + actualRepoName + "] 的有效克隆地址，请检查仓库权限。";
            }

            // 执行先删历史、后新增克隆
            File clonedDir = forceRecloneRepository(actualRepoId, actualRepoName, branch, cloneUrl);

            // 统计本地文件数量
            long fileCount = Constants.ZERO_LONG;
            try (Stream<Path> stream = Files.walk(clonedDir.toPath())) {
                fileCount = stream.filter(Files::isRegularFile).count();
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            sb.append("✅ **代码库分支克隆成功**\n\n");
            sb.append("- **代码库**: `").append(actualRepoName).append("` (ID: ").append(actualRepoId).append(")");
            if (repoDefaulted) {
                sb.append(" _(未指定仓库，使用默认 changtai-cloud)_");
            }
            sb.append("\n");
            sb.append("- **克隆分支**: `").append(branch).append("`");
            if (branchDefaulted) {
                sb.append(" _(未指定分支，使用默认 dev)_");
            }
            sb.append("\n");
            sb.append("- **历史处理**: 已清空旧版本历史缓存（先删后增）\n");
            sb.append("- **文件数量**: 成功检出 `").append(fileCount).append("` 个文件\n");
            sb.append("- **缓存锁定**: 本地缓存锁定 `2 小时`（后续大模型代码扫描秒级响应）\n");
            sb.append("- **存储路径**: `").append(clonedDir.getAbsolutePath().replace("\\", "/")).append("`\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("克隆分支异常: {}", e.getMessage(), e);
            return "❌ 克隆分支失败: " + e.getMessage();
        }
    }

    /**
     * 判断字符串是否具有常见分支名的特征
     *
     * @param str 待检测字符串
     * @return 是否符合分支名特征
     */
    private boolean isLikelyBranchName(String str) {
        if (StringUtils.isBlank(str)) {
            return false;
        }
        String lower = str.toLowerCase();
        return lower.contains("/")
                || lower.equals("dev")
                || lower.equals("master")
                || lower.equals("main")
                || lower.equals("test")
                || lower.equals("sit")
                || lower.equals("uat")
                || lower.equals("prod")
                || lower.startsWith("feature")
                || lower.startsWith("release")
                || lower.startsWith("hotfix")
                || lower.startsWith("bugfix")
                || lower.startsWith("fix");
    }

    /**
     * 解析并匹配代码库
     *
     * @param repoIdOrName 代码库ID或名称
     * @return 代码库实体
     */
    private YunxiaoRepositoryDTO resolveRepository(String repoIdOrName) {
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

        YunxiaoRepositoryQueryParam queryParam = new YunxiaoRepositoryQueryParam();
        queryParam.setSearch(repoIdOrName);
        queryParam.setPage(Constants.ONE_LONG);
        queryParam.setPerPage(Constants.TWENTY_LONG);

        List<YunxiaoRepositoryDTO> repos = yunxiaoDevopsService.listRepositories(queryParam);
        if (CollectionUtils.isEmpty(repos)) {
            throw new IllegalArgumentException("未找到匹配的代码库: " + repoIdOrName);
        }

        for (YunxiaoRepositoryDTO repo : repos) {
            if (StringUtils.equalsIgnoreCase(repo.getName(), repoIdOrName)
                    || StringUtils.equalsIgnoreCase(repo.getPath(), repoIdOrName)) {
                return repo;
            }
        }

        return repos.get(Constants.ZERO);
    }
}
