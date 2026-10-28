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
     * 命中关键词的方法声明行得分加成
     */
    private static final double DECLARATION_BOOST = 3.0;

    /**
     * Java 方法声明行
     */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(");

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
        List<FileMatch> matches = new ArrayList<>();

        // 1. 毫秒级遍历本地全量文件树
        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<File> candidates = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isAllowedCodeFile)
                    .collect(Collectors.toList());

            log.info("本地工程源码文件检索完毕，待扫描源码文件总数: {}", candidates.size());

            // 2. 内存全文扫描，统计每个 Token 在路径与内容中的命中情况
            matches = collectMatches(repoDir, candidates, searchTokens);

            // 3. 若初次检索未命中任何文件，借助大模型语义推理推断英文 token 进行二次兜底检索
            if (matches.isEmpty()) {
                Set<String> aiTokens = codeSearchKeywordService.inferEnglishTokensWithAI(searchKeyword);
                if (CollectionUtils.isNotEmpty(aiTokens)) {
                    log.info("初次扫描未命中，补充大模型推断英文 Token 二次检索: {}", aiTokens);
                    searchTokens.addAll(aiTokens);
                    matches = collectMatches(repoDir, candidates, searchTokens);
                }
            }
        } catch (Exception e) {
            log.error("本地文件扫描异常: {}", e.getMessage(), e);
        }

        log.info("本地全量匹配完成，命中关键词 [{}] 的源码文件总数: {}", searchKeyword, matches.size());

        // 4. 按相关度排序：命中越专属的 Token（如 拆分/spilt）得分越高，泛化 Token（如 statement/bill）得分低；
        //    同分时路径命中优先，再按 Controller > Service > Mapper/XML > 其他
        Map<String, Double> tokenWeights = computeTokenWeights(searchTokens, matches);
        for (FileMatch match : matches) {
            match.score = scoreMatch(match, tokenWeights);
        }
        matches.sort(Comparator.comparingDouble((FileMatch m) -> -m.score)
                .thenComparing(m -> m.pathTokens.isEmpty())
                .thenComparingInt(m -> layerOrder(m.relativePath)));
        if (!matches.isEmpty()) {
            log.info("相关度最高的文件: {}", matches.stream().limit(Constants.FIVE)
                    .map(m -> m.relativePath + "(" + String.format("%.1f", m.score) + ")")
                    .collect(Collectors.joining(", ")));
        }

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
                String snippet = extractLocalSnippet(content, tokenWeights, maxChars);

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
     * 统计每个候选文件在路径与内容中命中的 Token 及次数
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
            String lowerPath = relativePath.toLowerCase();
            String lowerContent;
            try {
                lowerContent = readContent(file).toLowerCase();
            } catch (Exception e) {
                log.debug("读取本地文件失败, 已跳过: {}", relativePath);
                continue;
            }
            for (String token : searchTokens) {
                if (lowerPath.contains(token)) {
                    match.pathTokens.add(token);
                }
                int count = countOccurrences(lowerContent, token);
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
     * 计算 Token 权重（IDF）：命中文件越少的 Token 越专属、权重越高
     *
     * @param searchTokens 检索 Token
     * @param matches      命中文件
     * @return Token -> 权重
     */
    private Map<String, Double> computeTokenWeights(Set<String> searchTokens, List<FileMatch> matches) {
        Map<String, Double> weights = new HashMap<>();
        for (String token : searchTokens) {
            long df = matches.stream()
                    .filter(m -> m.pathTokens.contains(token) || m.contentCounts.containsKey(token))
                    .count();
            if (df > Constants.ZERO) {
                weights.put(token, Math.log(Constants.ONE + (double) matches.size() / df));
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
     * 文本得分：文本中包含的 Token 权重之和
     */
    private double scoreText(String lowerText, Map<String, Double> tokenWeights) {
        double score = Constants.ZERO;
        for (Map.Entry<String, Double> entry : tokenWeights.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                score += entry.getValue();
            }
        }
        return score;
    }

    /**
     * 统计子串出现次数
     */
    private int countOccurrences(String text, String token) {
        if (StringUtils.isEmpty(token)) {
            return Constants.ZERO;
        }
        int count = Constants.ZERO;
        int index = text.indexOf(token);
        while (index >= Constants.ZERO) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
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
     * 提取本地代码上下文片段：优先截取命中专属 Token 最多的代码行附近，跳过 import/package 行
     *
     * @param content      完整代码
     * @param tokenWeights Token 权重
     * @param maxChars     片段最大字符数
     * @return 截取后的代码片段
     */
    private String extractLocalSnippet(String content, Map<String, Double> tokenWeights, int maxChars) {
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
        List<Integer> hitLines = new ArrayList<>();
        double[] lineScores = new double[lines.length];
        boolean[] declarationHits = new boolean[lines.length];
        boolean[] importLines = new boolean[lines.length];
        for (int i = Constants.ZERO; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                importLines[i] = true;
                continue;
            }
            lineScores[i] = scoreText(lines[i].toLowerCase(), tokenWeights);
            if (lineScores[i] > Constants.ZERO) {
                // 方法名命中关键词（如 generateSplitData）通常是业务主干，按方法名计分并优先纳入，
                // 避免参数类型中的泛化 Token（如 ScmHeadStatementAccountDetail）抬高无关方法
                Matcher declaration = METHOD_DECLARATION.matcher(lines[i]);
                if (declaration.find()) {
                    double nameScore = scoreText(declaration.group(Constants.ONE).toLowerCase(), tokenWeights);
                    if (nameScore > Constants.ZERO) {
                        lineScores[i] = nameScore * DECLARATION_BOOST;
                        declarationHits[i] = true;
                    }
                }
                hitLines.add(i);
            }
        }

        // 2. 按行得分从高到低依次纳入「命中行 ± 15 行」窗口，直到达到长度上限
        hitLines.sort(Comparator.comparingDouble((Integer i) -> -lineScores[i]));
        Set<Integer> selected = new TreeSet<>();
        int selectedChars = Constants.ZERO;
        boolean truncated = false;
        for (Integer center : hitLines) {
            // 方法声明向下多取，覆盖方法体开头的主流程；普通命中行取前后 15 行
            boolean declaration = declarationHits[center];
            int start = Math.max(Constants.ZERO, center - (declaration ? Constants.THREE : Constants.FIFTEEN));
            int end = Math.min(lines.length - Constants.ONE, center + (declaration ? Constants.FIFTY : Constants.FIFTEEN));
            int windowChars = Constants.ZERO;
            // 窗口内的 import/package 行同样不纳入
            for (int k = start; k <= end; k++) {
                if (!importLines[k] && !selected.contains(k)) {
                    windowChars += lines[k].length() + Constants.EIGHT;
                }
            }
            if (selectedChars + windowChars > maxChars) {
                truncated = true;
                break;
            }
            for (int k = start; k <= end; k++) {
                if (!importLines[k]) {
                    selected.add(k);
                }
            }
            selectedChars += windowChars;
        }
        if (selected.isEmpty()) {
            // 无行级命中（仅路径命中）时取前 100 行
            for (int i = Constants.ZERO; i < Math.min(lines.length, Constants.HUNDRED); i++) {
                selected.add(i);
            }
        }

        // 3. 按行号顺序输出，附带行号方便大模型精准定位
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
