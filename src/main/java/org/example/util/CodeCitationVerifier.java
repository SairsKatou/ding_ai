package org.example.util;

import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分析报告代码引用核对工具：核对「文件名.java:行号」引用的文件是否存在、行号是否在文件范围内
 *
 * @author zeymo
 */
@Slf4j
public final class CodeCitationVerifier {

    /**
     * 报告中的代码引用：文件名.java:行号、文件名.xml:行号、文件名.java:起始行-结束行（兼容全角冒号）
     */
    private static final Pattern CODE_CITATION = Pattern.compile(
            "\\b([A-Za-z][\\w$-]*\\.(?:java|xml))\\s*[:：]\\s*(\\d{1,7})(?:\\s*[-~]\\s*(\\d{1,7}))?");

    /**
     * 核对引用时跳过的目录
     */
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "node_modules", ".idea", ".mvn", "bin");

    /**
     * 报告末尾最多列出的核对未通过引用数
     */
    private static final int MAX_WARNINGS = Constants.TEN;

    private CodeCitationVerifier() {
    }

    /**
     * 核对报告中的代码引用，文件不存在或行号超出文件总行数时在报告末尾追加提示
     *
     * @param report     大模型分析报告
     * @param repoDir    本地仓库目录
     * @param knownPaths 已知相关文件的相对路径（优先在其中查找，未命中时再索引整个仓库）
     * @return 追加核对提示后的报告
     */
    public static String appendWarnings(String report, File repoDir, Collection<String> knownPaths) {
        if (StringUtils.isBlank(report) || repoDir == null || !repoDir.isDirectory()) {
            return report;
        }
        Map<String, Integer> citations = new LinkedHashMap<>();
        Matcher matcher = CODE_CITATION.matcher(report);
        while (matcher.find()) {
            int line = Integer.parseInt(matcher.group(Constants.TWO));
            if (matcher.group(Constants.THREE) != null) {
                line = Math.max(line, Integer.parseInt(matcher.group(Constants.THREE)));
            }
            citations.merge(matcher.group(Constants.ONE), line, Math::max);
        }
        if (citations.isEmpty()) {
            return report;
        }

        Path root = repoDir.toPath();
        Map<String, List<Path>> filesByName = new HashMap<>();
        for (String knownPath : knownPaths) {
            if (StringUtils.isBlank(knownPath)) {
                continue;
            }
            Path path = root.resolve(knownPath).normalize();
            if (path.getFileName() != null) {
                filesByName.computeIfAbsent(path.getFileName().toString(), key -> new ArrayList<>()).add(path);
            }
        }
        boolean repoIndexed = false;

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> citation : citations.entrySet()) {
            String fileName = citation.getKey();
            int line = citation.getValue();
            if (!filesByName.containsKey(fileName) && !repoIndexed) {
                indexRepoFiles(root, filesByName);
                repoIndexed = true;
            }
            List<Path> candidates = filesByName.getOrDefault(fileName, Collections.emptyList());
            if (candidates.isEmpty()) {
                warnings.add(fileName + ":" + line + "（分支中不存在该文件）");
                continue;
            }
            int maxLines = candidates.stream().mapToInt(CodeCitationVerifier::countLines).max().orElse(Constants.ZERO);
            if (line < Constants.ONE || line > maxLines) {
                warnings.add(fileName + ":" + line + "（行号超出文件总行数 " + maxLines + "）");
            }
        }
        if (warnings.isEmpty()) {
            return report;
        }

        log.warn("分析报告中有 {} 处代码引用核对未通过: {}", warnings.size(), warnings);
        StringBuilder sb = new StringBuilder(report.trim());
        sb.append("\n\n⚠️ 以下代码引用未能在分支源码中核对通过，请以源码为准：\n");
        warnings.stream().limit(MAX_WARNINGS).forEach(w -> sb.append("- ").append(w).append("\n"));
        if (warnings.size() > MAX_WARNINGS) {
            sb.append("- ……另有 ").append(warnings.size() - MAX_WARNINGS).append(" 处\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按文件名索引仓库内的源码文件（跳过 .git、target、node_modules 等目录）
     */
    private static void indexRepoFiles(Path root, Map<String, List<Path>> filesByName) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(root) && SKIP_DIRS.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") || name.endsWith(".xml")) {
                        List<Path> paths = filesByName.computeIfAbsent(name, key -> new ArrayList<>());
                        if (!paths.contains(file.normalize())) {
                            paths.add(file.normalize());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("索引仓库文件异常: {}", e.getMessage());
        }
    }

    /**
     * 统计文件行数，读取失败时返回 0
     */
    private static int countLines(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return content.isEmpty() ? Constants.ZERO : content.split("\\r?\\n", -1).length;
        } catch (IOException e) {
            return Constants.ZERO;
        }
    }
}
