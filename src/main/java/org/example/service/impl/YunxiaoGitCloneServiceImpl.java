package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.YunxiaoProperties;
import org.example.domain.dto.CodeAgentSeedDTO;
import org.example.domain.dto.CodeSearchTermsDTO;
import org.example.domain.dto.YunxiaoCodeSnippetDTO;
import org.example.domain.dto.YunxiaoRepoCacheMetadata;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.CodeSearchKeywordService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.example.util.CodeTokenMatcher;
import org.example.util.JavaSourceParser;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * 单文件代码片段最大字符数，避免个别大文件挤占 Prompt 上下文
     */
    private static final int MAX_SNIPPET_CHARS = Constants.FOUR_THOUSAND * Constants.TWO;

    /**
     * 相关度最高的前 N 个文件片段长度翻倍，保证核心实现类有足够代码
     */
    private static final int TOP_SNIPPET_FILES = Constants.THREE;

    /**
     * 路径命中 Token 的得分加成（相当于内容命中约 e^2 次）
     */
    private static final double PATH_HIT_BONUS = 2.0;

    /**
     * 方法名命中关键词时的得分加成
     */
    private static final double DECLARATION_BOOST = 3.0;

    /**
     * 文件路径命中业务实体词、且声明了动作方法（如 addReimbursement）时的得分倍数
     */
    private static final double ACTION_COMBO_FACTOR = 1.5;

    /**
     * 问题不涉及前端时，前端文件（.vue/.js/.ts）的得分倍数
     */
    private static final double FRONTEND_PENALTY = 0.3;

    /**
     * 被相关方法调用的同类方法，按调用方得分的该比例纳入片段
     */
    private static final double CALLEE_SCORE_RATIO = 0.5;

    /**
     * 长方法拆分后，方法内命中行窗口相对方法得分的比例（低于方法头）
     */
    private static final double SPLIT_SCORE_RATIO = 0.9;

    /**
     * BM25 IDF 平滑项
     */
    private static final double IDF_SMOOTHING = 0.5;

    /**
     * 同类方法调用：foo( 或 this.foo(
     */
    private static final Pattern METHOD_CALL = Pattern.compile("(?:(?<![\\w$.])|(?<=\\bthis\\.))([a-z_$][\\w$]*)\\s*\\(");

    /**
     * 问题涉及测试代码
     */
    private static final Pattern TEST_QUESTION_PATTERN = Pattern.compile("测试|单测|单元测试|test", Pattern.CASE_INSENSITIVE);

    /**
     * 问题涉及前端代码
     */
    private static final Pattern FRONTEND_QUESTION_PATTERN = Pattern.compile(
            "前端|页面|界面|按钮|弹窗|表单|列表页|组件|vue|\\bjs\\b|\\bts\\b", Pattern.CASE_INSENSITIVE);

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
     * @param question      用户原始提问（用于补充检索词、动作词与文件类型过滤）
     * @param maxFiles      最大提取相关文件数
     * @return 命中的代码片段列表
     */
    @Override
    public List<YunxiaoCodeSnippetDTO> scanLocalRepository(File repoDir, String searchKeyword, String question, int maxFiles) {
        if (repoDir == null || !repoDir.exists() || !repoDir.isDirectory()) {
            return Collections.emptyList();
        }
        LocalRanking ranking = rankLocalFiles(repoDir, searchKeyword, question);
        List<FileMatch> matches = ranking.matches();

        // 5. 提取代码片段：相关度最高的前几个文件给予更大的片段长度
        List<YunxiaoCodeSnippetDTO> snippets = new ArrayList<>();
        int limit = Math.min(matches.size(), maxFiles);

        for (int i = Constants.ZERO; i < limit; i++) {
            File file = matches.get(i).file;
            String relativePath = matches.get(i).relativePath;
            try {
                String content = readContent(file);
                // 相关度第 1 的文件通常是核心实现类，给 4 倍长度；第 2~3 个给 2 倍
                int maxChars = i == Constants.ZERO ? MAX_SNIPPET_CHARS * Constants.FOUR
                        : (i < TOP_SNIPPET_FILES ? MAX_SNIPPET_CHARS * Constants.TWO : MAX_SNIPPET_CHARS);
                String snippet = extractLocalSnippet(content, relativePath, ranking.tokenWeights(), ranking.actionTokens(), maxChars);

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
     * 为深度扫描 Agent 准备初始线索：英文检索词、相关度最高的文件、首个 Java 文件的方法列表
     *
     * @param repoDir       本地仓库根目录
     * @param searchKeyword 搜索业务关键词
     * @param question      用户原始提问
     * @param topFiles      返回的相关文件数
     * @return 初始线索；仓库目录不存在时返回空对象
     */
    @Override
    public CodeAgentSeedDTO buildAgentSeed(File repoDir, String searchKeyword, String question, int topFiles) {
        CodeAgentSeedDTO seed = new CodeAgentSeedDTO();
        if (repoDir == null || !repoDir.isDirectory()) {
            return seed;
        }
        LocalRanking ranking = rankLocalFiles(repoDir, searchKeyword, question);
        ranking.searchTokens().stream()
                .filter(token -> token.matches("[a-z0-9_]+"))
                .sorted(Comparator.comparingDouble((String token) -> -ranking.tokenWeights().getOrDefault(token, 0.0)))
                .forEach(seed.getEnglishTokens()::add);
        seed.getActionTokens().addAll(ranking.actionTokens());
        ranking.matches().stream().limit(topFiles).forEach(m -> seed.getTopFiles().add(m.relativePath));

        // 相关度最高的 Java 文件：列出方法，Agent 可直接 read_method
        for (FileMatch match : ranking.matches().subList(Constants.ZERO, Math.min(topFiles, ranking.matches().size()))) {
            if (!match.relativePath.endsWith(".java")) {
                continue;
            }
            try {
                String[] lines = readContent(match.file).split("\r?\n");
                seed.setTopFilePath(match.relativePath);
                for (JavaSourceParser.MethodRange method : JavaSourceParser.listMethods(lines)) {
                    seed.getTopFileMethods().add(method.name() + " (行 " + (method.startLine() + Constants.ONE)
                            + "-" + (method.endLine() + Constants.ONE) + ")");
                }
            } catch (IOException e) {
                log.warn("读取深度扫描线索文件失败, file: {}, error: {}", match.relativePath, e.getMessage());
            }
            break;
        }
        return seed;
    }

    /**
     * 本地全量检索并按相关度排序
     *
     * @param repoDir       本地仓库根目录
     * @param searchKeyword 搜索业务关键词
     * @param question      用户原始提问
     * @return 排序结果
     */
    private LocalRanking rankLocalFiles(File repoDir, String searchKeyword, String question) {
        String fullQuestion = StringUtils.defaultIfBlank(question, searchKeyword);
        Set<String> searchTokens = new HashSet<>(codeSearchKeywordService.buildSearchTokens(searchKeyword, fullQuestion));
        Set<String> actionTokens = new HashSet<>(codeSearchKeywordService.resolveActionTokens(fullQuestion));
        boolean includeTests = TEST_QUESTION_PATTERN.matcher(fullQuestion).find();
        boolean frontendQuestion = FRONTEND_QUESTION_PATTERN.matcher(fullQuestion).find();
        List<FileMatch> matches = new ArrayList<>();
        int candidateCount = Constants.ZERO;

        // 1. 毫秒级遍历本地全量文件树（问题不涉及测试时排除测试代码）
        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<File> candidates = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isAllowedCodeFile)
                    .filter(file -> includeTests || !isTestFile(getRelativePath(repoDir, file)))
                    .collect(Collectors.toList());
            candidateCount = candidates.size();

            log.info("本地工程源码文件检索完毕，待扫描源码文件总数: {}", candidateCount);

            // 2. 内存全文扫描，统计每个 Token 在路径与内容中的命中情况
            matches = collectMatches(repoDir, candidates, searchTokens);

            // 3. 业务字典未覆盖、无命中或无文件路径命中时，借助大模型推断英文实体词与动作词补充检索
            if (needsAiTerms(searchTokens, matches)) {
                CodeSearchTermsDTO aiTerms = codeSearchKeywordService.inferSearchTermsWithAI(fullQuestion);
                actionTokens.addAll(aiTerms.getActionTokens());
                if (searchTokens.addAll(aiTerms.getEntityTokens())) {
                    log.info("补充大模型推断实体 Token 二次检索: {}", aiTerms.getEntityTokens());
                    matches = collectMatches(repoDir, candidates, searchTokens);
                }
            }
        } catch (Exception e) {
            log.error("本地文件扫描异常: {}", e.getMessage(), e);
        }

        log.info("本地全量匹配完成，命中关键词 [{}] 的源码文件总数: {}，动作词: {}", searchKeyword, matches.size(), actionTokens);

        // 4. 按相关度排序：IDF 以全部候选文件为基数，命中越专属的 Token 得分越高，泛化 Token（如 log/statement）接近 0；
        //    路径命中实体词且声明了动作方法（如 addReimbursement）的文件额外加权；问题不涉及前端时前端文件降权；
        //    同分时路径命中优先，再按 Controller > Service > Mapper/XML > 其他
        Map<String, Double> tokenWeights = computeTokenWeights(searchTokens, matches, candidateCount);
        for (FileMatch match : matches) {
            match.score = scoreMatch(match, tokenWeights);
            if (!match.pathTokens.isEmpty() && hasActionDeclaration(match.file, actionTokens)) {
                match.score *= ACTION_COMBO_FACTOR;
            }
            if (!frontendQuestion && isFrontendFile(match.relativePath)) {
                match.score *= FRONTEND_PENALTY;
            }
        }
        matches.sort(Comparator.comparingDouble((FileMatch m) -> -m.score)
                .thenComparing(m -> m.pathTokens.isEmpty())
                .thenComparingInt(m -> layerOrder(m.relativePath)));
        if (!matches.isEmpty()) {
            log.info("相关度最高的文件: {}", matches.stream().limit(Constants.FIVE)
                    .map(m -> m.relativePath + "(" + String.format("%.2f", m.score) + ")")
                    .collect(Collectors.joining(", ")));
        }

        return new LocalRanking(matches, tokenWeights, searchTokens, actionTokens);
    }

    /**
     * 本地检索排序结果
     *
     * @param matches      按相关度降序排列的命中文件
     * @param tokenWeights Token 权重
     * @param searchTokens 检索 Token（含大模型补充的实体词）
     * @param actionTokens 动作词
     */
    private record LocalRanking(List<FileMatch> matches, Map<String, Double> tokenWeights,
                                Set<String> searchTokens, Set<String> actionTokens) {
    }

    /**
     * 判断是否需要借助大模型补充检索词：没有英文 Token、没有命中文件、或没有任何文件路径命中
     */
    private boolean needsAiTerms(Set<String> searchTokens, List<FileMatch> matches) {
        boolean hasEnglish = searchTokens.stream().anyMatch(token -> token.matches("[a-z0-9_]+"));
        boolean hasPathHit = matches.stream().anyMatch(m -> !m.pathTokens.isEmpty());
        return !hasEnglish || matches.isEmpty() || !hasPathHit;
    }

    /**
     * 是否为测试代码
     */
    private boolean isTestFile(String relativePath) {
        String path = relativePath.toLowerCase();
        String name = path.substring(path.lastIndexOf('/') + Constants.ONE);
        return path.startsWith("src/test/") || path.contains("/src/test/")
                || name.endsWith("test.java") || name.endsWith("tests.java")
                || name.contains(".spec.") || name.contains(".test.");
    }

    /**
     * 是否为前端代码
     */
    private boolean isFrontendFile(String relativePath) {
        String path = relativePath.toLowerCase();
        return path.endsWith(".vue") || path.endsWith(".js") || path.endsWith(".ts");
    }

    /**
     * Java 文件中是否声明了方法名包含动作词的方法（如 addReimbursement、preCheckReimbursement）
     */
    private boolean hasActionDeclaration(File file, Set<String> actionTokens) {
        if (actionTokens.isEmpty() || !file.getName().endsWith(".java")) {
            return false;
        }
        try {
            String[] lines = readContent(file).split("\r?\n");
            return JavaSourceParser.listMethods(lines).stream()
                    .anyMatch(method -> actionTokens.stream().anyMatch(action -> CodeTokenMatcher.contains(method.name(), action)));
        } catch (IOException e) {
            return false;
        }
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
     * 统计每个候选文件在路径与内容中命中的 Token 及次数（英文 Token 按词边界匹配，避免 log 命中 catalog）
     *
     * @param repoDir      仓库根目录
     * @param candidates   候选文件
     * @param searchTokens 检索 Token（均为小写）
     * @return 至少命中一个 Token 的文件
     */
    private List<FileMatch> collectMatches(File repoDir, List<File> candidates, Set<String> searchTokens) {
        List<FileMatch> matches = new ArrayList<>();
        for (File file : candidates) {
            String relativePath = getRelativePath(repoDir, file);
            FileMatch match = new FileMatch(file, relativePath);
            String content;
            try {
                content = readContent(file);
            } catch (Exception e) {
                log.debug("读取本地文件失败, 已跳过: {}", relativePath);
                continue;
            }
            String lowerContent = content.toLowerCase();
            for (String token : searchTokens) {
                if (CodeTokenMatcher.contains(relativePath, token)) {
                    match.pathTokens.add(token);
                }
                int count = CodeTokenMatcher.countMatches(content, lowerContent, token);
                if (count > Constants.ZERO) {
                    match.contentCounts.put(token, count);
                }
            }
            if (!match.pathTokens.isEmpty() || !match.contentCounts.isEmpty()) {
                matches.add(match);
            }
        }
        return matches;
    }

    /**
     * 计算 Token 权重（BM25 IDF，以全部候选文件为基数）：命中文件越少越专属；几乎每个文件都命中的泛化 Token 权重接近 0
     *
     * @param searchTokens   检索 Token
     * @param matches        命中文件
     * @param candidateCount 候选文件总数
     * @return Token -> 权重
     */
    private Map<String, Double> computeTokenWeights(Set<String> searchTokens, List<FileMatch> matches, int candidateCount) {
        Map<String, Double> weights = new HashMap<>();
        double total = Math.max(candidateCount, matches.size());
        for (String token : searchTokens) {
            long df = matches.stream()
                    .filter(m -> m.pathTokens.contains(token) || m.contentCounts.containsKey(token))
                    .count();
            if (df > Constants.ZERO) {
                weights.put(token, Math.log(Constants.ONE + (total - df + IDF_SMOOTHING) / (df + IDF_SMOOTHING)));
            }
        }
        return weights;
    }

    /**
     * 文件相关度得分：Σ 权重 × (路径命中加成 + log(1 + 内容命中次数))
     */
    private double scoreMatch(FileMatch match, Map<String, Double> tokenWeights) {
        double score = Constants.ZERO;
        for (Map.Entry<String, Double> entry : tokenWeights.entrySet()) {
            String token = entry.getKey();
            double tf = Math.log(Constants.ONE + match.contentCounts.getOrDefault(token, Constants.ZERO));
            double pathBonus = match.pathTokens.contains(token) ? PATH_HIT_BONUS : Constants.ZERO;
            score += entry.getValue() * (pathBonus + tf);
        }
        return score;
    }

    /**
     * 文本得分：文本中按词边界包含的 Token 权重之和
     */
    private double scoreText(String text, Map<String, Double> tokenWeights) {
        double score = Constants.ZERO;
        for (Map.Entry<String, Double> entry : tokenWeights.entrySet()) {
            if (CodeTokenMatcher.contains(text, entry.getKey())) {
                score += entry.getValue();
            }
        }
        return score;
    }

    /**
     * 按路径判断代码分层：Controller > Service > Mapper/XML > 其他
     */
    private int layerOrder(String relativePath) {
        String path = relativePath.toLowerCase();
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
    }

    /**
     * 以 UTF-8 读取文件，非法字节按替换字符处理（兼容少量 GBK 文件）
     */
    private String readContent(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 提取本地代码上下文片段：Java 文件按方法整体截取（命中行所在方法 + 被其调用的同类方法），
     * 其他文件取命中行附近窗口；跳过 import/package 行
     *
     * @param content      完整代码
     * @param relativePath 文件相对路径
     * @param tokenWeights Token 权重
     * @param actionTokens 动作词
     * @param maxChars     片段最大字符数
     * @return 截取后的代码片段
     */
    private String extractLocalSnippet(String content, String relativePath, Map<String, Double> tokenWeights,
                                       Set<String> actionTokens, int maxChars) {
        String[] lines = content.split("\r?\n");
        if (content.length() <= Constants.FOUR_THOUSAND) {
            // 短文件保留全文，同样附带行号，保证大模型引用的行号准确
            List<String> numbered = new ArrayList<>(lines.length);
            for (int i = Constants.ZERO; i < lines.length; i++) {
                numbered.add(String.format("L%d: %s", i + Constants.ONE, lines[i]));
            }
            return String.join("\n", numbered);
        }

        // 1. 为每个命中行打分：行内命中 Token 的权重之和（import/package 行不计分，避免片段被导包占满）
        double[] lineScores = new double[lines.length];
        boolean[] importLines = new boolean[lines.length];
        for (int i = Constants.ZERO; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                importLines[i] = true;
                continue;
            }
            lineScores[i] = scoreText(lines[i], tokenWeights);
        }

        // 2. 构建候选片段：Java 文件以方法为单位，其他文件以命中行 ± 15 行为窗口
        List<SnippetSegment> segments = relativePath.endsWith(".java")
                ? buildMethodSegments(lines, lineScores, tokenWeights, actionTokens)
                : buildWindowSegments(lines, lineScores, null, Constants.FIFTEEN);

        // 3. 按得分从高到低纳入，直到达到长度上限；放不下的长方法退化为「方法头 + 方法内命中行窗口」
        Set<Integer> selected = new TreeSet<>();
        boolean truncated = selectSegments(segments, lines, lineScores, importLines, selected, maxChars);
        if (selected.isEmpty()) {
            // 无行级命中（仅路径命中）时取前 100 行
            for (int i = Constants.ZERO; i < Math.min(lines.length, Constants.HUNDRED); i++) {
                selected.add(i);
            }
        }

        // 4. 按行号顺序输出，附带行号方便大模型精准定位
        StringBuilder result = new StringBuilder();
        int prev = -Constants.ONE;
        for (Integer idx : selected) {
            if (prev != -Constants.ONE && idx > prev + Constants.ONE) {
                result.append("// ... [此处省略无关行] ...\n");
            }
            result.append(String.format("L%d: %s", idx + Constants.ONE, lines[idx])).append("\n");
            prev = idx;
        }
        if (truncated) {
            result.append("// ... [片段过长已截断，仅保留与关键词最相关的代码] ...");
        }
        return result.toString().stripTrailing();
    }

    /**
     * Java 文件按方法构建候选片段：
     * 方法得分 = max(方法名命中得分 × 声明加成, 方法体内最高行得分)，方法名含动作词时再加权；
     * 相关方法调用的同类方法按调用方得分的一半纳入（只追一层）；方法外的命中行（字段、类声明）取 ± 3 行小窗口
     */
    private List<SnippetSegment> buildMethodSegments(String[] lines, double[] lineScores,
                                                     Map<String, Double> tokenWeights, Set<String> actionTokens) {
        List<JavaSourceParser.MethodRange> methods = JavaSourceParser.listMethods(lines);
        if (methods.isEmpty()) {
            return buildWindowSegments(lines, lineScores, null, Constants.FIFTEEN);
        }

        double[] methodScores = new double[methods.size()];
        boolean[] inMethod = new boolean[lines.length];
        Map<String, List<Integer>> methodsByName = new HashMap<>();
        for (int m = Constants.ZERO; m < methods.size(); m++) {
            JavaSourceParser.MethodRange method = methods.get(m);
            methodsByName.computeIfAbsent(method.name(), key -> new ArrayList<>()).add(m);
            double maxLineScore = Constants.ZERO;
            for (int i = method.startLine(); i <= method.endLine(); i++) {
                inMethod[i] = true;
                maxLineScore = Math.max(maxLineScore, lineScores[i]);
            }
            double score = Math.max(scoreText(method.name(), tokenWeights) * DECLARATION_BOOST, maxLineScore);
            if (score > Constants.ZERO
                    && actionTokens.stream().anyMatch(action -> CodeTokenMatcher.contains(method.name(), action))) {
                score *= ACTION_COMBO_FACTOR;
            }
            methodScores[m] = score;
        }

        // 追一层调用：校验、落库等逻辑常拆在私有方法里，方法名不一定包含关键词
        double[] finalScores = methodScores.clone();
        for (int m = Constants.ZERO; m < methods.size(); m++) {
            if (methodScores[m] <= Constants.ZERO) {
                continue;
            }
            JavaSourceParser.MethodRange caller = methods.get(m);
            for (int i = caller.declarationLine() + Constants.ONE; i <= caller.endLine(); i++) {
                Matcher call = METHOD_CALL.matcher(lines[i]);
                while (call.find()) {
                    for (Integer callee : methodsByName.getOrDefault(call.group(Constants.ONE), Collections.emptyList())) {
                        if (callee != m) {
                            finalScores[callee] = Math.max(finalScores[callee], methodScores[m] * CALLEE_SCORE_RATIO);
                        }
                    }
                }
            }
        }

        List<SnippetSegment> segments = new ArrayList<>();
        for (int m = Constants.ZERO; m < methods.size(); m++) {
            if (finalScores[m] > Constants.ZERO) {
                JavaSourceParser.MethodRange method = methods.get(m);
                segments.add(new SnippetSegment(method.startLine(), method.endLine(), finalScores[m], method));
            }
        }
        segments.addAll(buildWindowSegments(lines, lineScores, inMethod, Constants.THREE));
        return segments;
    }

    /**
     * 以命中行为中心构建 ± radius 行窗口
     *
     * @param excluded 不作为窗口中心的行（如已由方法片段覆盖），可为 null
     */
    private List<SnippetSegment> buildWindowSegments(String[] lines, double[] lineScores, boolean[] excluded, int radius) {
        List<SnippetSegment> segments = new ArrayList<>();
        for (int i = Constants.ZERO; i < lines.length; i++) {
            if (lineScores[i] > Constants.ZERO && (excluded == null || !excluded[i])) {
                segments.add(new SnippetSegment(Math.max(Constants.ZERO, i - radius),
                        Math.min(lines.length - Constants.ONE, i + radius), lineScores[i], null));
            }
        }
        return segments;
    }

    /**
     * 按得分从高到低纳入片段，直到达到长度上限
     *
     * @return 是否有相关片段因长度上限被舍弃
     */
    private boolean selectSegments(List<SnippetSegment> segments, String[] lines, double[] lineScores,
                                   boolean[] importLines, Set<Integer> selected, int maxChars) {
        PriorityQueue<SnippetSegment> queue = new PriorityQueue<>(
                Comparator.comparingDouble((SnippetSegment s) -> -s.score).thenComparingInt(s -> s.start));
        queue.addAll(segments);
        int selectedChars = Constants.ZERO;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            SnippetSegment segment = queue.poll();
            // 每行额外计入行号前缀，每个片段额外计入一个省略标记，保证输出不超过上限
            int cost = Constants.ZERO;
            for (int k = segment.start; k <= segment.end; k++) {
                if (!importLines[k] && !selected.contains(k)) {
                    cost += lines[k].length() + Constants.TEN;
                }
            }
            if (cost == Constants.ZERO) {
                continue;
            }
            cost += Constants.THIRTY;
            if (selectedChars + cost > maxChars) {
                truncated = true;
                if (segment.method != null) {
                    queue.addAll(splitMethod(segment, lines, lineScores));
                }
                continue;
            }
            for (int k = segment.start; k <= segment.end; k++) {
                if (!importLines[k]) {
                    selected.add(k);
                }
            }
            selectedChars += cost;
        }
        return truncated;
    }

    /**
     * 方法体超出长度上限时拆分为：方法头（注解 + 声明 + 前 30 行主流程）与方法内命中行 ± 15 行窗口
     */
    private List<SnippetSegment> splitMethod(SnippetSegment segment, String[] lines, double[] lineScores) {
        JavaSourceParser.MethodRange method = segment.method;
        int headEnd = Math.min(method.endLine(), method.declarationLine() + Constants.THIRTY);
        List<SnippetSegment> parts = new ArrayList<>();
        parts.add(new SnippetSegment(method.startLine(), headEnd, segment.score, null));

        double maxLineScore = Constants.ZERO;
        for (int i = headEnd + Constants.ONE; i <= method.endLine(); i++) {
            maxLineScore = Math.max(maxLineScore, lineScores[i]);
        }
        for (int i = headEnd + Constants.ONE; i <= method.endLine() && maxLineScore > Constants.ZERO; i++) {
            if (lineScores[i] > Constants.ZERO) {
                parts.add(new SnippetSegment(Math.max(headEnd + Constants.ONE, i - Constants.FIFTEEN),
                        Math.min(method.endLine(), i + Constants.FIFTEEN),
                        segment.score * SPLIT_SCORE_RATIO * lineScores[i] / maxLineScore, null));
            }
        }
        return parts;
    }

    /**
     * 候选代码片段（行下标从 0 开始，含首尾）
     */
    private static final class SnippetSegment {

        private final int start;
        private final int end;
        private final double score;
        /**
         * 片段对应的完整方法，窗口片段为 null
         */
        private final JavaSourceParser.MethodRange method;

        private SnippetSegment(int start, int end, double score, JavaSourceParser.MethodRange method) {
            this.start = start;
            this.end = end;
            this.score = score;
            this.method = method;
        }
    }

    /**
     * 单个文件的关键词命中情况
     */
    private static final class FileMatch {

        private final File file;
        private final String relativePath;
        /**
         * 路径中命中的 Token
         */
        private final Set<String> pathTokens = new HashSet<>();
        /**
         * 内容中命中的 Token 及次数
         */
        private final Map<String, Integer> contentCounts = new HashMap<>();
        private double score;

        private FileMatch(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }
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
