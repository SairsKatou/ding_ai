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
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * 允许扫描的代码文件后缀列表
     */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".java", ".xml", ".vue", ".js", ".ts", ".sql", ".yml", ".properties"
    );

    /**
     * 忽略的排除目录集合
     */
    private static final List<String> EXCLUDE_DIRS = Arrays.asList(
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

        // 工具 1: search_files
        JSONObject searchFilesProps = new JSONObject();
        JSONObject patternProp = new JSONObject();
        patternProp.put("type", "string");
        patternProp.put("description", "文件名或路径子串关键词，如 AllocationOrder 或 mapper");
        searchFilesProps.put("pattern", patternProp);

        JSONObject searchFilesParams = new JSONObject();
        searchFilesParams.put("type", "object");
        searchFilesParams.put("properties", searchFilesProps);
        searchFilesParams.put("required", Collections.singletonList("pattern"));

        tools.add(OpenChatToolDTO.builder()
                .type("function")
                .function(OpenChatFunctionDTO.builder()
                        .name(TOOL_SEARCH_FILES)
                        .description("在本地代码工程中模糊查找匹配的文件路径列表。例如输入 Allocation 或 allocation_order，返回工程中所有匹配的文件相对路径。")
                        .parameters(searchFilesParams)
                        .build())
                .build());

        // 工具 2: grep_code
        JSONObject grepCodeProps = new JSONObject();
        JSONObject keywordProp = new JSONObject();
        keywordProp.put("type", "string");
        keywordProp.put("description", "要搜索的类名、方法名、表名或业务关键字，如 scm_inventory_statement 或 allocation_order");
        grepCodeProps.put("keyword", keywordProp);

        JSONObject grepCodeParams = new JSONObject();
        grepCodeParams.put("type", "object");
        grepCodeParams.put("properties", grepCodeProps);
        grepCodeParams.put("required", Collections.singletonList("keyword"));

        tools.add(OpenChatToolDTO.builder()
                .type("function")
                .function(OpenChatFunctionDTO.builder()
                        .name(TOOL_GREP_CODE)
                        .description("在本地工程所有源码中全文检索指定关键字，返回包含该词的文件路径、匹配行号及具体代码内容。")
                        .parameters(grepCodeParams)
                        .build())
                .build());

        // 工具 3: read_file_range
        JSONObject readFileProps = new JSONObject();
        JSONObject filePathProp = new JSONObject();
        filePathProp.put("type", "string");
        filePathProp.put("description", "相对文件路径，如 src/main/java/com/ct/scm/entity/AllocationOrder.java");
        readFileProps.put("file_path", filePathProp);

        JSONObject startLineProp = new JSONObject();
        startLineProp.put("type", "integer");
        startLineProp.put("description", "起始行号（从 1 开始），默认 1");
        readFileProps.put("start_line", startLineProp);

        JSONObject lineCountProp = new JSONObject();
        lineCountProp.put("type", "integer");
        lineCountProp.put("description", "读取行数，默认 50 行，最大 100 行");
        readFileProps.put("line_count", lineCountProp);

        JSONObject readFileParams = new JSONObject();
        readFileParams.put("type", "object");
        readFileParams.put("properties", readFileProps);
        readFileParams.put("required", Collections.singletonList("file_path"));

        tools.add(OpenChatToolDTO.builder()
                .type("function")
                .function(OpenChatFunctionDTO.builder()
                        .name(TOOL_READ_FILE_RANGE)
                        .description("读取指定相对路径源码文件在指定行号区间内的代码（每行带行号前缀）。")
                        .parameters(readFileParams)
                        .build())
                .build());

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
        if (repoDir == null || !repoDir.exists()) {
            return "错误: 本地仓库目录不存在";
        }

        JSONObject args = new JSONObject();
        try {
            if (StringUtils.isNotBlank(argumentsJson)) {
                args = JSON.parseObject(argumentsJson);
            }
        } catch (Exception e) {
            log.warn("解析工具入参 JSON 异常: {}", argumentsJson);
        }

        switch (toolName) {
            case TOOL_SEARCH_FILES:
                String pattern = args.getString("pattern");
                return searchFiles(pattern, repoDir);
            case TOOL_GREP_CODE:
                String keyword = args.getString("keyword");
                return grepCode(keyword, repoDir);
            case TOOL_READ_FILE_RANGE:
                String filePath = args.getString("file_path");
                Integer startLine = args.getInteger("start_line");
                Integer lineCount = args.getInteger("line_count");
                return readFileRange(filePath, startLine, lineCount, repoDir);
            default:
                return "未知工具名称: " + toolName;
        }
    }

    /**
     * 模糊查找匹配的文件路径列表
     *
     * @param pattern 匹配关键词
     * @param repoDir 本地仓库根目录
     * @return 匹配的文件相对路径列表
     */
    private String searchFiles(String pattern, File repoDir) {
        if (StringUtils.isBlank(pattern)) {
            return "错误: 检索 pattern 不能为空";
        }

        String lowerPattern = pattern.trim().toLowerCase();
        List<String> matched = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<File> files = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isAllowedCodeFile)
                    .collect(Collectors.toList());

            for (File file : files) {
                String relativePath = getRelativePath(repoDir, file);
                if (relativePath.toLowerCase().contains(lowerPattern)) {
                    matched.add(relativePath);
                    if (matched.size() >= Constants.FIFTEEN) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
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
        return sb.toString();
    }

    /**
     * 全局检索指定关键字
     *
     * @param keyword 检索关键字
     * @param repoDir 本地仓库根目录
     * @return 命中的代码位置与行号
     */
    private String grepCode(String keyword, File repoDir) {
        if (StringUtils.isBlank(keyword)) {
            return "错误: 检索 keyword 不能为空";
        }

        String lowerKeyword = keyword.trim().toLowerCase();
        List<String> hits = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(repoDir.toPath())) {
            List<File> files = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isAllowedCodeFile)
                    .collect(Collectors.toList());

            for (File file : files) {
                if (hits.size() >= Constants.FIFTEEN) {
                    break;
                }
                String relativePath = getRelativePath(repoDir, file);
                try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                    String line;
                    int lineNumber = Constants.ZERO;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        if (line.toLowerCase().contains(lowerKeyword)) {
                            hits.add(String.format("%s:%d: %s", relativePath, lineNumber, line.trim()));
                            if (hits.size() >= Constants.FIFTEEN) {
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
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
        return sb.toString();
    }

    /**
     * 按行读取文件片段
     *
     * @param filePath   相对文件路径
     * @param startLine  起始行号
     * @param lineCount  行数
     * @param repoDir    本地仓库根目录
     * @return 带行号的代码文本
     */
    private String readFileRange(String filePath, Integer startLine, Integer lineCount, File repoDir) {
        if (StringUtils.isBlank(filePath)) {
            return "错误: 文件路径不能为空";
        }

        // 处理跨平台路径分隔符
        String normalizedPath = filePath.trim().replace("\\", "/");
        File targetFile = new File(repoDir, normalizedPath);
        if (!targetFile.exists() || !targetFile.isFile()) {
            return "错误: 文件不存在 -> " + filePath;
        }

        int start = (startLine != null && startLine > Constants.ZERO) ? startLine : Constants.ONE;
        int count = (lineCount != null && lineCount > Constants.ZERO) ? Math.min(lineCount, Constants.HUNDRED) : Constants.FIFTY;
        int end = start + count - Constants.ONE;

        StringBuilder sb = new StringBuilder();
        sb.append("【文件内容: ").append(normalizedPath).append(" (行 ").append(start).append("-").append(end).append(")】\n");

        try (BufferedReader reader = new BufferedReader(new FileReader(targetFile, StandardCharsets.UTF_8))) {
            String line;
            int currentLine = Constants.ZERO;
            int readCount = Constants.ZERO;

            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= start && currentLine <= end) {
                    sb.append(String.format("%4d: %s\n", currentLine, line));
                    readCount++;
                }
                if (currentLine > end) {
                    break;
                }
            }

            if (readCount == Constants.ZERO) {
                return "文件「" + filePath + "」总行数小于起始行号 " + start;
            }
        } catch (Exception e) {
            log.error("读取文件异常: {}", e.getMessage(), e);
            return "读取文件异常: " + e.getMessage();
        }

        return sb.toString();
    }

    /**
     * 判断是否是允许扫描的代码文件
     *
     * @param file 文件对象
     * @return 是否允许
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
     * 计算文件相对于仓库根目录的路径
     *
     * @param repoDir 根目录
     * @param file    文件
     * @return 相对路径
     */
    private String getRelativePath(File repoDir, File file) {
        String root = repoDir.getAbsolutePath().replace("\\", "/");
        String current = file.getAbsolutePath().replace("\\", "/");
        if (current.startsWith(root)) {
            String relative = current.substring(root.length());
            return relative.startsWith("/") ? relative.substring(Constants.ONE) : relative;
        }
        return file.getName();
    }
}
