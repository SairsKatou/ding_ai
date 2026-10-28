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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String TOOL_FIND_TABLE_USAGES = "find_table_usages";
    private static final String TOOL_GET_MAPPER_SQL = "get_mapper_sql";
    private static final String TOOL_READ_METHOD = "read_method";

    /**
     * find_table_usages 最多展示的表数量 / 每张表最多展示的引用位置数
     */
    private static final int MAX_TABLES = Constants.THIRTY;
    private static final int MAX_LOCATIONS_PER_TABLE = Constants.FIVE;

    /**
     * read_method 单个方法最多输出行数 / 最多展示的同名重载数
     */
    private static final int MAX_METHOD_LINES = Constants.TWO_HUNDRED;
    private static final int MAX_METHOD_MATCHES = Constants.THREE;

    /**
     * get_mapper_sql 单条语句最多输出行数 / 最多展开的 include 片段数
     */
    private static final int MAX_STATEMENT_LINES = Constants.HUNDRED;
    private static final int MAX_INCLUDE_FRAGMENTS = Constants.TEN;

    /**
     * 实体类上的 @TableName("xxx") / @TableName(value = "xxx")
     */
    private static final Pattern TABLE_NAME_ANNOTATION = Pattern.compile(
            "@TableName\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    /**
     * 类声明，用于把 @TableName 关联到实体类名
     */
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+(\\w+)");

    /**
     * SQL 中 FROM/JOIN/INTO/UPDATE/TABLE 后面紧跟的表名（排除 XML 标签 <update> 与方法名 updateById 等）
     */
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?<![\\w</.])(from|join|into|update|table(?:\\s+if\\s+(?:not\\s+)?exists)?)\\s+`?([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)?)`?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 判断 Java 字符串字面量是否确实是 SQL 的辅助特征
     */
    private static final Pattern SQL_HINT = Pattern.compile(
            "\\b(select|insert|delete|where|set|values|join|group\\s+by|order\\s+by)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Java 字符串字面量
     */
    private static final Pattern JAVA_STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * MyBatis-Plus 通过实体间接操作表的写法
     */
    private static final Pattern BASE_MAPPER_ENTITY = Pattern.compile("\\b(?:BaseMapper|IService)\\s*<\\s*(\\w+)\\s*>");
    private static final Pattern SERVICE_IMPL_ENTITY = Pattern.compile("\\bServiceImpl\\s*<\\s*\\w+\\s*,\\s*(\\w+)\\s*>");
    private static final Pattern WRAPPER_ENTITY = Pattern.compile(
            "\\b(?:LambdaQueryWrapper|LambdaUpdateWrapper|QueryWrapper|UpdateWrapper|LambdaQueryChainWrapper)\\s*<\\s*(\\w+)\\s*>");
    private static final Pattern WRAPPERS_LAMBDA_ENTITY = Pattern.compile(
            "\\bWrappers\\.<\\s*(\\w+)\\s*>\\s*lambda(?:Query|Update)|\\bWrappers\\.lambda(?:Query|Update)\\s*\\(\\s*(\\w+)\\.class");

    /**
     * 被误识别为表名的 SQL 关键字 / MyBatis 片段
     */
    private static final Set<String> NON_TABLE_WORDS = Set.of(
            "select", "where", "set", "values", "value", "dual", "if", "not", "exists", "on", "as",
            "left", "right", "inner", "outer", "join", "and", "or", "the", "table", "into", "from", "update"
    );

    /**
     * Mapper XML 中的命名空间与 SQL 语句
     */
    private static final Pattern MAPPER_NAMESPACE = Pattern.compile("<mapper\\s+[^>]*namespace\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MAPPER_STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*?\\bid\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern INCLUDE_REFID = Pattern.compile("<include\\s+[^>]*refid\\s*=\\s*\"([^\"]+)\"");

    /**
     * 方法声明行中方法名之前不应出现的字符（排除赋值、调用、return 等语句）
     */
    private static final Pattern NON_DECLARATION_PREFIX = Pattern.compile(
            "[=.(!:+\"]|->|&&|\\|\\||//|^\\*|\\b(?:return|new|throw|if|while|for|switch|catch|else)\\b");

    /**
     * 与方法写在同一行的注解，如 @GetMapping("/list")
     */
    private static final Pattern INLINE_ANNOTATION = Pattern.compile("@\\w+(?:\\s*\\([^)]*\\))?");

    /**
     * XML 注释
     */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

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

        JSONObject tableUsageProps = new JSONObject();
        tableUsageProps.put("keyword", buildProperty("string",
                "英文业务关键词、表名、实体类名或模块路径，如 allocation、AllocationOrder、inventory；不传则列出全工程引用最多的表"));
        tools.add(buildTool(TOOL_FIND_TABLE_USAGES,
                "一次性汇总工程中的「数据库表名 → 实体类 → 引用代码位置(文件:行号)」，来源包括 @TableName、Mapper XML/注解 SQL、"
                        + "BaseMapper/ServiceImpl 泛型、QueryWrapper 等写法。表名、实体名或引用文件路径命中关键词即返回，适合作为探查第一步。",
                tableUsageProps));

        JSONObject mapperSqlProps = new JSONObject();
        mapperSqlProps.put("mapper", buildProperty("string", "Mapper 接口简单类名或全限定名，如 AllocationOrderMapper"));
        mapperSqlProps.put("method", buildProperty("string", "Mapper 方法名（即 XML 语句 id），如 selectPageList；不传则列出该 Mapper 的全部语句 id"));
        tools.add(buildTool(TOOL_GET_MAPPER_SQL,
                "定位 Mapper 接口对应的 MyBatis XML，返回指定方法的完整 SQL（含行号），并自动展开 <include refid> 引用的 <sql> 片段。",
                mapperSqlProps, "mapper"));

        JSONObject readMethodProps = new JSONObject();
        readMethodProps.put("file_path", buildProperty("string", "相对文件路径，如 src/main/java/com/ct/scm/service/impl/AllocationOrderServiceImpl.java"));
        readMethodProps.put("method_name", buildProperty("string", "方法名，如 audit；存在重载时返回全部同名方法"));
        tools.add(buildTool(TOOL_READ_METHOD,
                "按方法名读取完整方法体（含注解与注释，带行号），自动匹配方法起止行，无需猜测行号范围。",
                readMethodProps, "file_path", "method_name"));

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
                case TOOL_FIND_TABLE_USAGES:
                    return findTableUsages(args.getString("keyword"), root);
                case TOOL_GET_MAPPER_SQL:
                    return getMapperSql(args.getString("mapper"), args.getString("method"), root);
                case TOOL_READ_METHOD:
                    return readMethod(args.getString("file_path"), args.getString("method_name"), root);
                default:
                    return "未知工具名称: " + toolName;
            }
        } catch (ToolArgumentException e) {
            return "错误: " + e.getMessage();
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
        Path targetFile = resolveRepoFile(filePath, root);
        String normalizedPath = getRelativePath(root, targetFile);

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
     * 汇总工程中的表名、实体类及引用位置
     *
     * @param keyword 过滤关键词，可为空
     * @param root    本地仓库根目录
     * @return 按表分组的引用清单
     */
    private String findTableUsages(String keyword, Path root) {
        // 实体类名 -> 表名
        Map<String, String> entityTables = new HashMap<>();
        // 表名 -> 引用信息
        Map<String, TableUsage> usages = new LinkedHashMap<>();
        // 实体间接引用，需等全部 @TableName 收集完成后再解析
        List<EntityRef> entityRefs = new ArrayList<>();

        try {
            walkCodeFiles(root, (file, attrs) -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (attrs.size() > MAX_GREP_FILE_SIZE
                        || !(name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".sql"))) {
                    return true;
                }
                try {
                    collectTableUsages(root, file, readContent(file), entityTables, usages, entityRefs);
                } catch (IOException e) {
                    log.debug("表引用扫描读取文件失败, 已跳过: {}", file, e);
                }
                return true;
            });
        } catch (IOException e) {
            log.error("表引用扫描异常: {}", e.getMessage(), e);
            return "表引用扫描异常: " + e.getMessage();
        }

        // 解析 BaseMapper<Entity> / Wrapper<Entity> 等实体引用
        for (EntityRef ref : entityRefs) {
            String table = entityTables.get(ref.entity());
            String kind = ref.kind();
            if (table == null && ref.strong()) {
                // 未声明 @TableName 时按 MyBatis-Plus 默认驼峰转下划线规则推断
                table = camelToSnake(ref.entity());
                kind = kind + "，表名按命名推断";
            }
            if (table != null) {
                TableUsage usage = usages.computeIfAbsent(table, TableUsage::new);
                usage.entities.add(ref.entity());
                usage.addLocation(ref.location(), kind, ref.snippet());
            }
        }
        entityTables.forEach((entity, table) -> usages.computeIfAbsent(table, TableUsage::new).entities.add(entity));

        String lowerKeyword = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        List<TableUsage> result = new ArrayList<>();
        for (TableUsage usage : usages.values()) {
            if (lowerKeyword.isEmpty() || usage.matches(lowerKeyword)) {
                result.add(usage);
            }
        }
        if (result.isEmpty()) {
            return StringUtils.isBlank(keyword)
                    ? "工程中未识别到任何数据库表引用。"
                    : "未找到与「" + keyword + "」相关的表引用，请尝试英文表名片段或实体类名。";
        }

        // 排序：表名等于关键词 > 以关键词开头 > 表名/实体名包含关键词 > 仅引用路径命中，同级按引用次数倒序
        result.sort(Comparator.comparingInt((TableUsage u) -> u.matchLevel(lowerKeyword))
                .thenComparing(u -> -u.locations.size()));

        StringBuilder sb = new StringBuilder();
        sb.append("识别到 ").append(result.size()).append(" 张相关表")
                .append(StringUtils.isBlank(keyword) ? "" : "（关键词「" + keyword + "」）").append(":\n");
        for (TableUsage usage : result.subList(Constants.ZERO, Math.min(result.size(), MAX_TABLES))) {
            sb.append("\n■ ").append(usage.table);
            if (!usage.entities.isEmpty()) {
                sb.append("  实体: ").append(String.join(", ", usage.entities));
            }
            sb.append("  (引用 ").append(usage.locations.size()).append(" 处)");
            if (!lowerKeyword.isEmpty() && usage.matchLevel(lowerKeyword) == Constants.THREE) {
                // 表名与实体名都不含关键词，仅在相关代码中被引用
                sb.append("  [关联表]");
            }
            sb.append("\n");
            int shown = Constants.ZERO;
            for (Map.Entry<String, String> location : usage.locations.entrySet()) {
                if (shown++ >= MAX_LOCATIONS_PER_TABLE) {
                    break;
                }
                sb.append("  - ").append(location.getKey()).append("  ").append(location.getValue()).append("\n");
            }
            if (usage.locations.size() > MAX_LOCATIONS_PER_TABLE) {
                sb.append("  - ...其余 ").append(usage.locations.size() - MAX_LOCATIONS_PER_TABLE)
                        .append(" 处可用 grep_code 检索该表名查看\n");
            }
        }
        if (result.size() > MAX_TABLES) {
            sb.append("\n（仅展示前 ").append(MAX_TABLES).append(" 张表，共 ").append(result.size())
                    .append(" 张，请使用更精确的关键词）\n");
        }
        return sb.toString();
    }

    /**
     * 扫描单个文件中的表引用
     */
    private void collectTableUsages(Path root, Path file, String content, Map<String, String> entityTables,
                                    Map<String, TableUsage> usages, List<EntityRef> entityRefs) {
        String relativePath = getRelativePath(root, file);
        String[] lines = content.split("\\r?\\n", -1);

        if (!relativePath.endsWith(".java")) {
            // XML 只扫描 MyBatis Mapper，注释掉的 SQL 不计入
            if (relativePath.endsWith(".xml") && !content.contains("<mapper")) {
                return;
            }
            String sql = maskXmlComments(content);
            int[] lineStarts = computeLineStarts(sql);
            Matcher matcher = SQL_TABLE.matcher(sql);
            while (matcher.find()) {
                String table = toTableName(sql, matcher);
                if (table != null) {
                    // 按表名所在行定位，兼容 FROM 与表名换行书写
                    int lineIndex = lineIndexOf(lineStarts, matcher.start(Constants.TWO));
                    usages.computeIfAbsent(table, TableUsage::new).addLocation(
                            relativePath + ":" + (lineIndex + Constants.ONE), "SQL", lines[lineIndex].trim());
                }
            }
            return;
        }

        String pendingTable = null;
        for (int i = Constants.ZERO; i < lines.length; i++) {
            String line = lines[i];
            String location = relativePath + ":" + (i + Constants.ONE);

            Matcher tableName = TABLE_NAME_ANNOTATION.matcher(line);
            if (tableName.find()) {
                pendingTable = tableName.group(Constants.ONE).replace("`", "").toLowerCase(Locale.ROOT);
                usages.computeIfAbsent(pendingTable, TableUsage::new).addLocation(location, "@TableName", line.trim());
            }
            if (pendingTable != null) {
                Matcher classDecl = CLASS_DECLARATION.matcher(line);
                if (classDecl.find()) {
                    entityTables.put(classDecl.group(Constants.ONE), pendingTable);
                    pendingTable = null;
                }
            }

            collectEntityRefs(BASE_MAPPER_ENTITY, "BaseMapper", true, line, location, entityRefs);
            collectEntityRefs(SERVICE_IMPL_ENTITY, "ServiceImpl", true, line, location, entityRefs);
            collectEntityRefs(WRAPPER_ENTITY, "Wrapper", false, line, location, entityRefs);
            collectEntityRefs(WRAPPERS_LAMBDA_ENTITY, "Wrapper", false, line, location, entityRefs);

            // 注解 SQL（@Select 等）或代码中拼接的 SQL 字符串
            Matcher literal = JAVA_STRING_LITERAL.matcher(line);
            while (literal.find()) {
                String text = literal.group(Constants.ONE);
                Matcher matcher = SQL_TABLE.matcher(text);
                while (matcher.find()) {
                    String table = toTableName(text, matcher);
                    if (table != null && looksLikeSql(text, matcher.group(Constants.ONE), table)) {
                        usages.computeIfAbsent(table, TableUsage::new).addLocation(location, "SQL", line.trim());
                    }
                }
            }
        }
    }

    /**
     * 收集通过实体类间接引用表的位置
     */
    private void collectEntityRefs(Pattern pattern, String kind, boolean strong, String line, String location,
                                   List<EntityRef> entityRefs) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String entity = matcher.group(Constants.ONE) != null ? matcher.group(Constants.ONE) : matcher.group(matcher.groupCount());
            // 排除 BaseMapper<T> 这类泛型占位符
            if (entity != null && entity.length() > Constants.ONE && Character.isUpperCase(entity.charAt(Constants.ZERO))) {
                entityRefs.add(new EntityRef(entity, location, kind, line.trim(), strong));
            }
        }
    }

    /**
     * 从 SQL_TABLE 匹配结果中提取规范化表名，非表名返回 null
     */
    private String toTableName(String text, Matcher matcher) {
        String table = matcher.group(Constants.TWO).toLowerCase(Locale.ROOT);
        if (NON_TABLE_WORDS.contains(table) || table.length() < Constants.TWO) {
            return null;
        }
        // 排除 ON DUPLICATE KEY UPDATE col = ...
        if ("update".equalsIgnoreCase(matcher.group(Constants.ONE))
                && text.substring(Constants.ZERO, matcher.start()).trim().toLowerCase(Locale.ROOT).endsWith("key")) {
            return null;
        }
        return table;
    }

    /**
     * 判断 Java 字符串是否为 SQL，避免把 "Data from server" 这类普通文本识别为表
     */
    private boolean looksLikeSql(String text, String keyword, String table) {
        return keyword.equals(keyword.toUpperCase(Locale.ROOT)) || table.contains("_") || SQL_HINT.matcher(text).find();
    }

    /**
     * 将 XML 注释替换为空格（保留换行以维持行号）
     */
    private String maskXmlComments(String content) {
        Matcher matcher = XML_COMMENT.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group().replaceAll("[^\\n]", " ")));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 计算每一行起始字符偏移量
     */
    private int[] computeLineStarts(String content) {
        List<Integer> starts = new ArrayList<>();
        starts.add(Constants.ZERO);
        for (int i = Constants.ZERO; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + Constants.ONE);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 根据字符偏移量计算所在行下标（从 0 开始）
     */
    private int lineIndexOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= Constants.ZERO ? index : -index - Constants.TWO;
    }

    /**
     * 驼峰类名转下划线表名，如 AllocationOrder -> allocation_order
     */
    private String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /**
     * 获取 Mapper 方法对应的 XML SQL
     *
     * @param mapper Mapper 接口名
     * @param method Mapper 方法名，可为空
     * @param root   本地仓库根目录
     * @return SQL 语句文本
     */
    private String getMapperSql(String mapper, String method, Path root) {
        if (StringUtils.isBlank(mapper)) {
            return "错误: mapper 不能为空";
        }
        String fullName = StringUtils.removeEnd(StringUtils.removeEnd(mapper.trim().replace("/", "."), ".java"), ".xml");
        String simpleName = StringUtils.substringAfterLast(fullName, ".");
        if (simpleName.isEmpty()) {
            simpleName = fullName;
        }
        String targetName = simpleName;

        List<Path> mapperXmls = new ArrayList<>();
        try {
            walkCodeFiles(root, (file, attrs) -> {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    try {
                        Matcher ns = MAPPER_NAMESPACE.matcher(readContent(file));
                        if (ns.find() && (ns.group(Constants.ONE).equals(fullName)
                                || ns.group(Constants.ONE).endsWith("." + targetName))) {
                            mapperXmls.add(file);
                        }
                    } catch (IOException e) {
                        log.debug("读取 Mapper XML 失败, 已跳过: {}", file, e);
                    }
                }
                return true;
            });
        } catch (IOException e) {
            log.error("Mapper XML 检索异常: {}", e.getMessage(), e);
            return "Mapper XML 检索异常: " + e.getMessage();
        }

        if (mapperXmls.isEmpty()) {
            return "未找到 namespace 为「" + mapper + "」的 Mapper XML。该 Mapper 可能只使用 MyBatis-Plus 内置方法或 @Select 注解，"
                    + "可用 read_method 直接读取 Mapper 接口方法。";
        }

        StringBuilder sb = new StringBuilder();
        for (Path xml : mapperXmls) {
            try {
                appendMapperSql(sb, root, xml, StringUtils.trimToNull(method));
            } catch (IOException e) {
                sb.append("读取 ").append(getRelativePath(root, xml)).append(" 异常: ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 输出单个 Mapper XML 中的语句列表或指定语句
     */
    private void appendMapperSql(StringBuilder sb, Path root, Path xml, String method) throws IOException {
        String relativePath = getRelativePath(root, xml);
        String raw = readContent(xml);
        // 定位用去注释后的内容（长度不变），展示用原文
        String content = maskXmlComments(raw);
        String[] lines = raw.split("\\r?\\n", -1);
        int[] lineStarts = computeLineStarts(content);

        Matcher statement = MAPPER_STATEMENT.matcher(content);
        if (method == null) {
            sb.append("【").append(relativePath).append(" 中的 SQL 语句】\n");
            int count = Constants.ZERO;
            while (statement.find()) {
                count++;
                sb.append(String.format("%4d: <%s id=\"%s\">\n",
                        lineIndexOf(lineStarts, statement.start()) + Constants.ONE, statement.group(Constants.ONE), statement.group(Constants.TWO)));
            }
            if (count == Constants.ZERO) {
                sb.append("（无 select/insert/update/delete 语句）\n");
            }
            return;
        }

        while (statement.find()) {
            if (!statement.group(Constants.TWO).equals(method)) {
                continue;
            }
            int endOffset = findClosingTag(content, statement.group(Constants.ONE), statement.end());
            String statementText = content.substring(statement.start(), endOffset);
            sb.append("【").append(relativePath).append(" -> ").append(method).append("】\n");
            appendNumberedLines(sb, lines, lineIndexOf(lineStarts, statement.start()),
                    lineIndexOf(lineStarts, Math.max(statement.start(), endOffset - Constants.ONE)), MAX_STATEMENT_LINES);
            appendIncludedSql(sb, content, lines, lineStarts, statementText);
            return;
        }
        sb.append("【").append(relativePath).append("】未找到 id 为「").append(method)
                .append("」的语句，该方法可能是 MyBatis-Plus 内置方法或使用注解 SQL。不传 method 可查看全部语句 id。\n");
    }

    /**
     * 展开语句中 <include refid> 引用的 <sql> 片段（支持嵌套引用）
     */
    private void appendIncludedSql(StringBuilder sb, String content, String[] lines, int[] lineStarts, String statementText) {
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        collectIncludeRefs(statementText, pending);

        while (!pending.isEmpty() && visited.size() < MAX_INCLUDE_FRAGMENTS) {
            String refId = pending.poll();
            if (!visited.add(refId)) {
                continue;
            }
            Matcher sql = Pattern.compile("<sql\\b[^>]*?\\bid\\s*=\\s*\"" + Pattern.quote(refId) + "\"").matcher(content);
            if (!sql.find()) {
                sb.append("（引用片段 ").append(refId).append(" 不在当前文件中，可用 grep_code 检索）\n");
                continue;
            }
            int endOffset = findClosingTag(content, "sql", sql.end());
            sb.append("【引用片段 <sql id=\"").append(refId).append("\">】\n");
            appendNumberedLines(sb, lines, lineIndexOf(lineStarts, sql.start()),
                    lineIndexOf(lineStarts, endOffset - Constants.ONE), MAX_STATEMENT_LINES);
            // 片段内可能继续 include 其他片段
            collectIncludeRefs(content.substring(sql.start(), endOffset), pending);
        }
    }

    /**
     * 提取 <include refid="xxx"> 中的片段 id（去掉 namespace 前缀）
     */
    private void collectIncludeRefs(String text, Deque<String> pending) {
        Matcher include = INCLUDE_REFID.matcher(text);
        while (include.find()) {
            String refId = include.group(Constants.ONE);
            pending.add(refId.contains(".") ? StringUtils.substringAfterLast(refId, ".") : refId);
        }
    }

    /**
     * 查找 XML 结束标签位置，找不到时返回内容末尾
     */
    private int findClosingTag(String content, String tag, int fromIndex) {
        int end = content.indexOf("</" + tag + ">", fromIndex);
        return end < Constants.ZERO ? content.length() : end + tag.length() + Constants.THREE;
    }

    /**
     * 按方法名读取完整方法体
     *
     * @param filePath   相对文件路径
     * @param methodName 方法名
     * @param root       本地仓库根目录
     * @return 带行号的方法源码
     */
    private String readMethod(String filePath, String methodName, Path root) {
        // 兼容 Class#method、Class.method、method() 等写法
        String name = StringUtils.trimToEmpty(StringUtils.substringBefore(methodName, "("));
        name = name.substring(Math.max(name.lastIndexOf('#'), name.lastIndexOf('.')) + Constants.ONE);
        if (!name.matches("[A-Za-z_$][\\w$]*")) {
            return "错误: method_name 不合法 -> " + methodName;
        }
        Path targetFile = resolveRepoFile(filePath, root);
        String relativePath = getRelativePath(root, targetFile);

        String[] lines;
        try {
            lines = readContent(targetFile).split("\\r?\\n", -1);
        } catch (IOException e) {
            log.error("读取文件异常: {}", e.getMessage(), e);
            return "读取文件异常: " + e.getMessage();
        }

        Pattern declaration = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\(");
        List<int[]> ranges = new ArrayList<>();
        for (int i = Constants.ZERO; i < lines.length && ranges.size() <= MAX_METHOD_MATCHES; i++) {
            Matcher matcher = declaration.matcher(lines[i]);
            if (matcher.find() && isMethodDeclaration(lines[i].substring(Constants.ZERO, matcher.start()))) {
                int end = findMethodEnd(lines, i);
                ranges.add(new int[]{findDeclarationStart(lines, i), end});
                i = end;
            }
        }
        if (ranges.isEmpty()) {
            return "在「" + relativePath + "」中未找到方法「" + name + "」的声明，可用 grep_code 检索该方法所在文件。";
        }

        StringBuilder sb = new StringBuilder();
        if (ranges.size() > Constants.ONE) {
            sb.append("找到 ").append(ranges.size() > MAX_METHOD_MATCHES ? "超过 " + MAX_METHOD_MATCHES : ranges.size())
                    .append(" 个同名方法「").append(name).append("」:\n");
        }
        for (int[] range : ranges.subList(Constants.ZERO, Math.min(ranges.size(), MAX_METHOD_MATCHES))) {
            sb.append("【方法: ").append(relativePath).append("#").append(name)
                    .append(" (行 ").append(range[0] + Constants.ONE).append("-").append(range[1] + Constants.ONE).append(")】\n");
            appendNumberedLines(sb, lines, range[0], range[1], MAX_METHOD_LINES);
        }
        return sb.toString();
    }

    /**
     * 根据方法名之前的内容判断是否是声明（而非调用、赋值、return 语句或注释）
     */
    private boolean isMethodDeclaration(String prefix) {
        String declarationPrefix = INLINE_ANNOTATION.matcher(prefix).replaceAll("").trim();
        return !declarationPrefix.isEmpty() && !NON_DECLARATION_PREFIX.matcher(declarationPrefix).find();
    }

    /**
     * 向上包含方法的注解与注释
     */
    private int findDeclarationStart(String[] lines, int declarationLine) {
        int start = declarationLine;
        for (int i = declarationLine - Constants.ONE; i >= Constants.ZERO && declarationLine - i <= Constants.THIRTY; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.endsWith(";") || line.endsWith("{") || line.endsWith("}")) {
                break;
            }
            start = i;
        }
        return start;
    }

    /**
     * 从方法声明行开始匹配大括号，返回方法结束行（跳过字符串、字符与注释中的括号）
     */
    private int findMethodEnd(String[] lines, int declarationLine) {
        int depth = Constants.ZERO;
        boolean bodyStarted = false;
        boolean inBlockComment = false;
        for (int i = declarationLine; i < lines.length; i++) {
            String line = lines[i];
            for (int j = Constants.ZERO; j < line.length(); j++) {
                char c = line.charAt(j);
                if (inBlockComment) {
                    if (c == '*' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '/') {
                        inBlockComment = false;
                        j++;
                    }
                } else if (c == '/' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '/') {
                    break;
                } else if (c == '/' && j + Constants.ONE < line.length() && line.charAt(j + Constants.ONE) == '*') {
                    inBlockComment = true;
                    j++;
                } else if (c == '"' || c == '\'') {
                    j = skipQuoted(line, j, c);
                } else if (c == ';' && !bodyStarted) {
                    // 接口 / 抽象方法没有方法体
                    return i;
                } else if (c == '{') {
                    depth++;
                    bodyStarted = true;
                } else if (c == '}') {
                    depth--;
                    if (bodyStarted && depth == Constants.ZERO) {
                        return i;
                    }
                }
            }
        }
        return lines.length - Constants.ONE;
    }

    /**
     * 跳过字符串或字符字面量，返回结束引号下标
     */
    private int skipQuoted(String line, int start, char quote) {
        for (int i = start + Constants.ONE; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return line.length();
    }

    /**
     * 输出带行号的行区间（下标从 0 开始，含首尾），超过上限时截断并提示
     */
    private void appendNumberedLines(StringBuilder sb, String[] lines, int startIndex, int endIndex, int maxLines) {
        int last = Math.min(endIndex, startIndex + maxLines - Constants.ONE);
        for (int i = startIndex; i <= last && i < lines.length; i++) {
            sb.append(String.format("%4d: %s\n", i + Constants.ONE, abbreviateLine(lines[i])));
        }
        if (last < endIndex) {
            sb.append("...（共 ").append(endIndex - startIndex + Constants.ONE).append(" 行，已截断，可用 read_file_range 从第 ")
                    .append(last + Constants.TWO).append(" 行继续读取）\n");
        }
    }

    /**
     * 将模型传入的相对路径解析为仓库内文件，防止 ../ 与符号链接越界
     *
     * @param filePath 相对文件路径
     * @param root     本地仓库根目录
     * @return 文件路径
     */
    private Path resolveRepoFile(String filePath, Path root) {
        if (StringUtils.isBlank(filePath)) {
            throw new ToolArgumentException("文件路径不能为空");
        }
        // 处理跨平台路径分隔符，并去掉开头的 "/" 统一按仓库相对路径解析
        String normalizedPath = StringUtils.stripStart(filePath.trim().replace("\\", "/"), "/");
        Path targetFile = root.resolve(normalizedPath).normalize();
        if (!targetFile.startsWith(root)) {
            throw new ToolArgumentException("文件路径越界，仅允许读取仓库内文件 -> " + filePath);
        }
        if (!Files.isRegularFile(targetFile)) {
            throw new ToolArgumentException("文件不存在 -> " + filePath);
        }
        if (!isInsideRepo(targetFile, root)) {
            throw new ToolArgumentException("文件路径越界，仅允许读取仓库内文件 -> " + filePath);
        }
        return targetFile;
    }

    /**
     * 以 UTF-8 读取完整文件内容，非法字节按替换字符处理
     */
    private String readContent(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
    private OpenChatToolDTO buildTool(String name, String description, JSONObject properties, String... requiredFields) {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", List.of(requiredFields));

        return OpenChatToolDTO.builder()
                .type("function")
                .function(OpenChatFunctionDTO.builder()
                        .name(name)
                        .description(description)
                        .parameters(params)
                        .build())
                .build();
    }

    /**
     * 单张表的引用汇总
     */
    private static final class TableUsage {

        private final String table;
        private final Set<String> entities = new TreeSet<>();
        /**
         * 引用位置(文件:行号) -> 类型与代码片段，按发现顺序保存并自动去重
         */
        private final Map<String, String> locations = new LinkedHashMap<>();

        private TableUsage(String table) {
            this.table = table;
        }

        private void addLocation(String location, String kind, String snippet) {
            locations.putIfAbsent(location, "[" + kind + "] " + StringUtils.abbreviate(snippet, Constants.HUNDRED));
        }

        private boolean nameMatches(String lowerKeyword) {
            // 忽略下划线比较，使 AllocationOrder 也能命中 allocation_order
            if (lowerKeyword.isEmpty() || table.replace("_", "").contains(lowerKeyword.replace("_", ""))) {
                return true;
            }
            for (String entity : entities) {
                if (entity.toLowerCase(Locale.ROOT).contains(lowerKeyword.replace("_", ""))) {
                    return true;
                }
            }
            return false;
        }

        private int matchLevel(String lowerKeyword) {
            String compactKeyword = lowerKeyword.replace("_", "");
            String compactTable = table.replace("_", "");
            if (!compactKeyword.isEmpty() && compactTable.equals(compactKeyword)) {
                return Constants.ZERO;
            }
            if (!compactKeyword.isEmpty() && compactTable.startsWith(compactKeyword)) {
                return Constants.ONE;
            }
            return nameMatches(lowerKeyword) ? Constants.TWO : Constants.THREE;
        }

        private boolean matches(String lowerKeyword) {
            if (nameMatches(lowerKeyword)) {
                return true;
            }
            // 引用文件路径命中关键词，用于发现同一业务模块中联动的流水表、日志表
            // 忽略下划线比较，使 actual_reimbursement 也能命中 ScmActualReimbursementServiceImpl.java
            String compactKeyword = lowerKeyword.replace("_", "");
            for (String location : locations.keySet()) {
                if (location.toLowerCase(Locale.ROOT).replace("_", "").contains(compactKeyword)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 通过实体类间接引用表的位置，strong 表示 BaseMapper/ServiceImpl 这类可按默认规则推断表名的引用
     */
    private record EntityRef(String entity, String location, String kind, String snippet, boolean strong) {
    }

    /**
     * 工具入参不合法，消息直接返回给大模型
     */
    private static final class ToolArgumentException extends RuntimeException {

        private ToolArgumentException(String message) {
            super(message);
        }
    }
}
