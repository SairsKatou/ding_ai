package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.domain.dto.OpenChatFunctionDTO;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.service.YunxiaoAgentToolService;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/**
 * 云效代码 Agent 本地执行工具服务实现类
 *
 * @author zeymo
 */
@Slf4j
@Service
public class YunxiaoAgentToolServiceImpl implements YunxiaoAgentToolService {

    private static final String TOOL_SEARCH_FILES = "search_files";
    private static final String TOOL_GREP_CODE = "grep_code";
    private static final String TOOL_READ_FILE_RANGE = "read_file_range";

    /**
     * 单次检索最大返回条数
     */
    private static final int MAX_RESULTS = Constants.FIFTEEN;

    /**
     * grep 跳过的大文件阈值（1MB），避免扫描打包产物或压缩脚本
     */
    private static final long MAX_GREP_FILE_SIZE = 1024L * 1024L;

    /**
     * 单行输出最大字符数，避免压缩代码等超长行撑爆上下文
     */
    private static final int MAX_LINE_LENGTH = Constants.FIVE_HUNDRED;

    /**
     * 允许扫描的代码文件后缀列表
     */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".java", ".xml", ".vue", ".js", ".ts", ".sql", ".yml", ".properties"
    );

    /**
     * 忽略的排除目录集合
     */
    private static final Set<String> EXCLUDE_DIRS = Set.of(
            ".git", "target", "node_modules", ".idea", ".mvn", "bin"
    );

    /**
     * 获取向大模型声明的可用工具清单与 Schema
     *
     * @return 工具声明列表
     */
    @Override
    public List<OpenChatToolDTO> getToolDefinitions() {
        List<OpenChatToolDTO> tools = new ArrayList<>();

        JSONObject searchFilesProps = new JSONObject();
        searchFilesProps.put("pattern", buildProperty("string", "文件名或路径子串关键词，如 AllocationOrder 或 mapper"));
        tools.add(buildTool(TOOL_SEARCH_FILES,
                "在本地代码工程中模糊查找匹配的文件路径列表。例如输入 Allocation 或 allocation_order，返回工程中所有匹配的文件相对路径。",
                searchFilesProps, "pattern"));

        JSONObject grepCodeProps = new JSONObject();
        grepCodeProps.put("keyword", buildProperty("string", "要搜索的类名、方法名、表名或业务关键字，如 scm_inventory_statement 或 allocation_order"));
        tools.add(buildTool(TOOL_GREP_CODE,
                "在本地工程所有源码中全文检索指定关键字，返回包含该词的文件路径、匹配行号及具体代码内容。",
                grepCodeProps, "keyword"));

        JSONObject readFileProps = new JSONObject();
        readFileProps.put("file_path", buildProperty("string", "相对文件路径，如 src/main/java/com/ct/scm/entity/AllocationOrder.java"));
        readFileProps.put("start_line", buildProperty("integer", "起始行号（从 1 开始），默认 1"));
        readFileProps.put("line_count", buildProperty("integer", "读取行数，默认 50 行，最大 100 行"));
        tools.add(buildTool(TOOL_READ_FILE_RANGE,
                "读取指定相对路径源码文件在指定行号区间内的代码（每行带行号前缀）。",
                readFileProps, "file_path"));

        return tools;
    }

    /**
     * 执行指定的本地代码探查工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 入参 JSON 字符串
     * @param repoDir       本地克隆仓库目录
     * @return 工具执行输出结果文本
     */
    @Override
    public String executeTool(String toolName, String argumentsJson, File repoDir) {
        if (repoDir == null || !repoDir.isDirectory()) {
            return "错误: 本地仓库目录不存在";
        }
        if (StringUtils.isBlank(toolName)) {
            return "错误: 工具名称不能为空";
        }

        JSONObject args;
        try {
            args = StringUtils.isBlank(argumentsJson) ? null : JSON.parseObject(argumentsJson);
        } catch (Exception e) {
            log.warn("解析工具入参 JSON 异常: {}", argumentsJson);
            return "错误: 工具入参不是合法的 JSON -> " + argumentsJson;
        }
        if (args == null) {
            args = new JSONObject();
        }

        Path root = repoDir.toPath().toAbsolutePath().normalize();
        try {
            switch (toolName) {
                case TOOL_SEARCH_FILES:
                    return searchFiles(args.getString("pattern"), root);
                case TOOL_GREP_CODE:
                    return grepCode(args.getString("keyword"), root);
                case TOOL_READ_FILE_RANGE:
                    return readFileRange(args.getString("file_path"), args.getInteger("start_line"),
                            args.getInteger("line_count"), root);
                default:
                    return "未知工具名称: " + toolName;
            }
        } catch (Exception e) {
            // 兜底：入参类型错误等异常不应中断 Agent 多轮对话
            log.error("工具 [{}] 执行异常, 入参: {}", toolName, argumentsJson, e);
            return "工具执行异常: " + e.getMessage();
        }
    }

    /**
     * 模糊查找匹配的文件路径列表
     *
     * @param pattern 匹配关键词
     * @param root    本地仓库根目录
     * @return 匹配的文件相对路径列表
     */
    private String searchFiles(String pattern, Path root) {
        if (StringUtils.isBlank(pattern)) {
            return "错误: 检索 pattern 不能为空";
        }

        String lowerPattern = pattern.trim().toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        boolean truncated;

        try {
            truncated = walkCodeFiles(root, (file, attrs) -> {
                String relativePath = getRelativePath(root, file);
                if (relativePath.toLowerCase(Locale.ROOT).contains(lowerPattern)) {
                    matched.add(relativePath);
                }
                return matched.size() < MAX_RESULTS;
            });
        } catch (IOException e) {
            log.error("本地文件搜索异常: {}", e.getMessage(), e);
            return "文件搜索异常: " + e.getMessage();
        }

        if (matched.isEmpty()) {
            return "未找到路径或文件名包含「" + pattern + "」的代码文件。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(matched.size()).append(" 个匹配的文件:\n");
        for (String path : matched) {
            sb.append("- ").append(path).append("\n");
        }
        appendTruncatedHint(sb, truncated);
        return sb.toString();
    }

    /**
     * 全局检索指定关键字
     *
     * @param keyword 检索关键字
     * @param root    本地仓库根目录
     * @return 命中的代码位置与行号
     */
    private String grepCode(String keyword, Path root) {
        if (StringUtils.isBlank(keyword)) {
            return "错误: 检索 keyword 不能为空";
        }

        String lowerKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        boolean truncated;

        try {
            truncated = walkCodeFiles(root, (file, attrs) -> {
                if (attrs.size() > MAX_GREP_FILE_SIZE) {
                    return true;
                }
                String relativePath = getRelativePath(root, file);
                try (BufferedReader reader = newReader(file)) {
                    String line;
                    int lineNumber = Constants.ZERO;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (line.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                            hits.add(relativePath + ":" + lineNumber + ": " + abbreviateLine(line.trim()));
                            if (hits.size() >= MAX_RESULTS) {
                                return false;
                            }
                        }
                    }
                } catch (IOException e) {
                    log.debug("grep 读取文件失败, 已跳过: {}", relativePath, e);
                }
                return true;
            });
        } catch (IOException e) {
            log.error("本地全文 grep 异常: {}", e.getMessage(), e);
            return "全文 grep 检索异常: " + e.getMessage();
        }

        if (hits.isEmpty()) {
            return "在所有源码文件中未检索到包含「" + keyword + "」的代码行。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("全局检索到 ").append(hits.size()).append(" 处包含「").append(keyword).append("」的代码行:\n");
        for (String hit : hits) {
            sb.append(hit).append("\n");
        }
        appendTruncatedHint(sb, truncated);
        return sb.toString();
    }

    /**
     * 按行读取文件片段
     *
     * @param filePath  相对文件路径
     * @param startLine 起始行号
     * @param lineCount 行数
     * @param root      本地仓库根目录
     * @return 带行号的代码文本
     */
    private String readFileRange(String filePath, Integer startLine, Integer lineCount, Path root) {
        if (StringUtils.isBlank(filePath)) {
            return "错误: 文件路径不能为空";
        }

        // 处理跨平台路径分隔符，并去掉开头的 "/" 统一按仓库相对路径解析
        String normalizedPath = StringUtils.stripStart(filePath.trim().replace("\\", "/"), "/");
        Path targetFile = root.resolve(normalizedPath).normalize();
        // 防止 ../ 等路径穿越读取仓库外文件
        if (!targetFile.startsWith(root)) {
            return "错误: 文件路径越界，仅允许读取仓库内文件 -> " + filePath;
        }
        if (!Files.isRegularFile(targetFile)) {
            return "错误: 文件不存在 -> " + filePath;
        }
        if (!isInsideRepo(targetFile, root)) {
            return "错误: 文件路径越界，仅允许读取仓库内文件 -> " + filePath;
        }

        int start = (startLine != null && startLine > Constants.ZERO) ? startLine : Constants.ONE;
        int count = (lineCount != null && lineCount > Constants.ZERO) ? Math.min(lineCount, Constants.HUNDRED) : Constants.FIFTY;
        int end = start + count - Constants.ONE;

        StringBuilder content = new StringBuilder();
        int lastLine = Constants.ZERO;
        try (BufferedReader reader = newReader(targetFile)) {
            String line;
            int currentLine = Constants.ZERO;
            while (currentLine < end && (line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= start) {
                    content.append(String.format("%4d: %s%n", currentLine, abbreviateLine(line)));
                    lastLine = currentLine;
                }
            }
            if (lastLine == Constants.ZERO) {
                return "文件「" + filePath + "」总行数 " + currentLine + " 小于起始行号 " + start;
            }
        } catch (IOException e) {
            log.error("读取文件异常: {}", e.getMessage(), e);
            return "读取文件异常: " + e.getMessage();
        }

        // 标题使用实际读取到的结束行号，避免文件末尾不足时误导模型
        return "【文件内容: " + normalizedPath + " (行 " + start + "-" + lastLine + ")】\n" + content;
    }

    /**
     * 遍历仓库内允许扫描的代码文件，命中排除目录时整棵子树跳过
     *
     * @param root    仓库根目录
     * @param visitor 文件访问回调，返回 false 表示终止遍历
     * @return 是否因回调要求而提前终止
     * @throws IOException 遍历异常
     */
    private boolean walkCodeFiles(Path root, BiPredicate<Path, BasicFileAttributes> visitor) throws IOException {
        AtomicBoolean terminated = new AtomicBoolean(false);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (!dir.equals(root) && name != null && EXCLUDE_DIRS.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || !isAllowedCodeFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!visitor.test(file, attrs)) {
                    terminated.set(true);
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件/目录无权限等问题不影响整体检索
                log.debug("访问文件失败, 已跳过: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        return terminated.get();
    }

    /**
     * 判断是否是允许扫描的代码文件
     *
     * @param file 文件路径
     * @return 是否允许
     */
    private boolean isAllowedCodeFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : ALLOWED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析符号链接后再次校验文件确实位于仓库内
     */
    private boolean isInsideRepo(Path file, Path root) {
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 计算文件相对于仓库根目录的路径（统一使用 "/" 分隔）
     *
     * @param root 根目录
     * @param file 文件
     * @return 相对路径
     */
    private String getRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace("\\", "/");
    }

    /**
     * 以 UTF-8 打开文件，非法字节按替换字符处理，兼容少量 GBK 等编码文件
     */
    private BufferedReader newReader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    /**
     * 截断超长代码行
     */
    private String abbreviateLine(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(Constants.ZERO, MAX_LINE_LENGTH) + " ...(已截断)" : line;
    }

    /**
     * 结果达到上限时追加提示，引导模型缩小检索范围
     */
    private void appendTruncatedHint(StringBuilder sb, boolean truncated) {
        if (truncated) {
            sb.append("（结果已达上限 ").append(MAX_RESULTS).append(" 条，可能还有更多匹配，请使用更精确的关键词）\n");
        }
    }

    /**
     * 构建工具参数属性 Schema
     */
    private JSONObject buildProperty(String type, String description) {
        JSONObject prop = new JSONObject();
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }

    /**
     * 构建 function 类型的工具声明
     */
    private OpenChatToolDTO buildTool(String name, String description, JSONObject properties, String requiredField) {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of(requiredField));

        return OpenChatToolDTO.builder()
                .type("function")
                .function(OpenChatFunctionDTO.builder()
                        .name(name)
                        .description(description)
                        .parameters(params)
                        .build())
                .build();
    }
}
