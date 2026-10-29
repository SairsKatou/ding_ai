package org.example.service;

import com.ct.common.core.constant.Constants;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.service.impl.YunxiaoAgentToolServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云效代码 Agent 本地执行工具单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoAgentToolServiceTest {

    @InjectMocks
    private YunxiaoAgentToolServiceImpl yunxiaoAgentToolService;

    private File tempRepoDir;

    @BeforeEach
    public void setUp() throws IOException {
        tempRepoDir = new File(System.getProperty("java.io.tmpdir"), "agent-tool-test-" + System.currentTimeMillis());
        tempRepoDir.mkdirs();

        // 构造虚拟代码文件
        File entityDir = new File(tempRepoDir, "src/main/java/com/ct/scm/entity");
        entityDir.mkdirs();

        File allocationOrderFile = new File(entityDir, "AllocationOrder.java");
        String entityCode = "package com.ct.scm.entity;\n"
                + "import com.baomidou.mybatisplus.annotation.TableName;\n"
                + "@TableName(\"allocation_order\")\n"
                + "public class AllocationOrder {\n"
                + "    private Long id;\n"
                + "    private String allocationNo;\n"
                + "}\n";
        Files.writeString(allocationOrderFile.toPath(), entityCode, StandardCharsets.UTF_8);
    }

    @AfterEach
    public void tearDown() {
        if (tempRepoDir != null && tempRepoDir.exists()) {
            try (Stream<Path> stream = Files.walk(tempRepoDir.toPath())) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 测试工具定义 Schema 生成
     */
    @Test
    public void testGetToolDefinitions() {
        List<OpenChatToolDTO> tools = yunxiaoAgentToolService.getToolDefinitions();
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
        // 至少包含 search_files, grep_code, read_file_range 3 个工具
        assertTrue(tools.size() >= Constants.THREE);
        assertTrue(tools.stream().anyMatch(t -> "find_table_usages".equals(t.getFunction().getName())));
        assertTrue(tools.stream().anyMatch(t -> "get_mapper_sql".equals(t.getFunction().getName())));
        assertTrue(tools.stream().anyMatch(t -> "read_method".equals(t.getFunction().getName())));
    }

    /**
     * 测试 find_table_usages 汇总 @TableName、XML SQL、Java SQL 字符串与 BaseMapper 引用
     */
    @Test
    public void testExecuteToolFindTableUsages() throws IOException {
        writeBusinessFixture();

        String output = yunxiaoAgentToolService.executeTool("find_table_usages", "{\"keyword\":\"allocation\"}", tempRepoDir);

        assertTrue(output.contains("allocation_order  实体: AllocationOrder"));
        assertTrue(output.contains("[@TableName]"));
        assertTrue(output.contains("[BaseMapper]"));
        // XML 中 join 的明细表
        assertTrue(output.contains("allocation_order_detail"));
        // 同模块 Service 中 SQL 字符串引用的联动流水表
        assertTrue(output.contains("inventory_statement"));
        // 普通文本与 XML 注释中的 from 不应识别为表
        assertFalse(output.contains("server"));
        assertFalse(output.contains("old_allocation_backup"));
    }

    /**
     * 测试 get_mapper_sql 读取指定语句并展开 include 片段
     */
    @Test
    public void testExecuteToolGetMapperSql() throws IOException {
        writeBusinessFixture();

        String output = yunxiaoAgentToolService.executeTool("get_mapper_sql",
                "{\"mapper\":\"AllocationOrderMapper\",\"method\":\"selectPageList\"}", tempRepoDir);
        assertTrue(output.contains("left join allocation_order_detail"));
        assertTrue(output.contains("</select>"));
        assertTrue(output.contains("<sql id=\"Base_Column_List\">"));
        assertFalse(output.contains("deleteByNo"));

        String list = yunxiaoAgentToolService.executeTool("get_mapper_sql", "{\"mapper\":\"AllocationOrderMapper\"}", tempRepoDir);
        assertTrue(list.contains("<select id=\"selectPageList\">"));
        assertTrue(list.contains("<delete id=\"deleteByNo\">"));
    }

    /**
     * 测试 read_method 读取完整方法体，忽略调用处与字符串中的大括号
     */
    @Test
    public void testExecuteToolReadMethod() throws IOException {
        writeBusinessFixture();
        String file = "src/main/java/com/ct/scm/service/impl/AllocationOrderServiceImpl.java";

        String output = yunxiaoAgentToolService.executeTool("read_method",
                "{\"file_path\":\"" + file + "\",\"method_name\":\"audit\"}", tempRepoDir);
        assertTrue(output.contains("@Transactional"));
        assertTrue(output.contains("insert into inventory_statement"));
        assertTrue(output.contains("log.info(\"audit done }\")"));
        assertFalse(output.contains("public void batchAudit"));

        String mapperMethod = yunxiaoAgentToolService.executeTool("read_method",
                "{\"file_path\":\"src/main/java/com/ct/scm/mapper/AllocationOrderMapper.java\",\"method_name\":\"selectPageList\"}",
                tempRepoDir);
        assertTrue(mapperMethod.contains("List<AllocationOrder> selectPageList(String no);"));

        String missing = yunxiaoAgentToolService.executeTool("read_method",
                "{\"file_path\":\"" + file + "\",\"method_name\":\"notExists\"}", tempRepoDir);
        assertTrue(missing.contains("未找到方法"));
    }

    /**
     * 构造 Mapper 接口、Mapper XML 与 Service 实现类
     */
    private void writeBusinessFixture() throws IOException {
        writeFile("src/main/java/com/ct/scm/mapper/AllocationOrderMapper.java",
                "package com.ct.scm.mapper;\n"
                        + "public interface AllocationOrderMapper extends BaseMapper<AllocationOrder> {\n"
                        + "    List<AllocationOrder> selectPageList(String no);\n"
                        + "}\n");
        writeFile("src/main/resources/mapper/AllocationOrderMapper.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mapper namespace=\"com.ct.scm.mapper.AllocationOrderMapper\">\n"
                        + "    <sql id=\"Base_Column_List\">a.id, a.allocation_no</sql>\n"
                        + "    <!-- select * from old_allocation_backup -->\n"
                        + "    <select id=\"selectPageList\">\n"
                        + "        select <include refid=\"Base_Column_List\"/>\n"
                        + "        from allocation_order a\n"
                        + "        left join allocation_order_detail d on d.order_id = a.id\n"
                        + "        where a.allocation_no = #{no}\n"
                        + "    </select>\n"
                        + "    <delete id=\"deleteByNo\">\n"
                        + "        delete from allocation_order where allocation_no = #{no}\n"
                        + "    </delete>\n"
                        + "</mapper>\n");
        writeFile("src/main/java/com/ct/scm/service/impl/AllocationOrderServiceImpl.java",
                "package com.ct.scm.service.impl;\n"
                        + "public class AllocationOrderServiceImpl {\n"
                        + "\n"
                        + "    /**\n"
                        + "     * 审核\n"
                        + "     */\n"
                        + "    @Transactional(rollbackFor = Exception.class)\n"
                        + "    public void audit(Long id) {\n"
                        + "        if (id == null) {\n"
                        + "            return;\n"
                        + "        }\n"
                        + "        jdbcTemplate.update(\"insert into inventory_statement(order_id) values (?)\", id);\n"
                        + "        log.info(\"audit done }\");\n"
                        + "    }\n"
                        + "\n"
                        + "    public void batchAudit(List<Long> ids) {\n"
                        + "        log.info(\"Data from server\");\n"
                        + "        ids.forEach(id -> audit(id));\n"
                        + "    }\n"
                        + "}\n");
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(tempRepoDir, relativePath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    /**
     * 测试 search_files 模糊搜索文件路径
     */
    @Test
    public void testExecuteToolSearchFiles() {
        String args = "{\"pattern\":\"Allocation\"}";
        String output = yunxiaoAgentToolService.executeTool("search_files", args, tempRepoDir);

        assertNotNull(output);
        assertTrue(output.contains("AllocationOrder.java"));
    }

    /**
     * 测试 grep_code 全局检索代码关键字
     */
    @Test
    public void testExecuteToolGrepCode() {
        String args = "{\"keyword\":\"allocation_order\"}";
        String output = yunxiaoAgentToolService.executeTool("grep_code", args, tempRepoDir);

        assertNotNull(output);
        assertTrue(output.contains("AllocationOrder.java"));
        assertTrue(output.contains("@TableName"));
    }

    /**
     * 测试 read_file_range 按行读取文件片段
     */
    @Test
    public void testExecuteToolReadFileRange() {
        String args = "{\"file_path\":\"src/main/java/com/ct/scm/entity/AllocationOrder.java\",\"start_line\":1,\"line_count\":10}";
        String output = yunxiaoAgentToolService.executeTool("read_file_range", args, tempRepoDir);

        assertNotNull(output);
        assertTrue(output.contains("AllocationOrder"));
        assertTrue(output.contains("1:"));
    }

    /**
     * 测试 read_file_range 拒绝 ../ 路径穿越
     */
    @Test
    public void testExecuteToolReadFileRangeRejectsPathTraversal() throws IOException {
        File outsideFile = new File(tempRepoDir.getParentFile(), tempRepoDir.getName() + "-secret.java");
        Files.writeString(outsideFile.toPath(), "secret", StandardCharsets.UTF_8);
        try {
            String args = "{\"file_path\":\"../" + outsideFile.getName() + "\"}";
            String output = yunxiaoAgentToolService.executeTool("read_file_range", args, tempRepoDir);

            assertTrue(output.contains("越界"));
            assertFalse(output.contains("secret\n"));
        } finally {
            outsideFile.delete();
        }
    }

    /**
     * 测试 read_file_range 标题按实际读取到的结束行号展示
     */
    @Test
    public void testExecuteToolReadFileRangeActualEndLine() {
        String args = "{\"file_path\":\"src/main/java/com/ct/scm/entity/AllocationOrder.java\",\"line_count\":50}";
        String output = yunxiaoAgentToolService.executeTool("read_file_range", args, tempRepoDir);

        assertTrue(output.contains("(行 1-7)"));
    }

    /**
     * 测试排除目录下的文件不会被检索
     */
    @Test
    public void testExecuteToolSkipsExcludedDirs() throws IOException {
        File targetDir = new File(tempRepoDir, "target/classes");
        targetDir.mkdirs();
        Files.writeString(new File(targetDir, "AllocationOrderCopy.java").toPath(), "allocation_order", StandardCharsets.UTF_8);

        String output = yunxiaoAgentToolService.executeTool("search_files", "{\"pattern\":\"Allocation\"}", tempRepoDir);

        assertTrue(output.contains("AllocationOrder.java"));
        assertFalse(output.contains("AllocationOrderCopy.java"));
    }

    /**
     * 测试非法 JSON 入参与空工具名称
     */
    @Test
    public void testExecuteToolInvalidInput() {
        assertTrue(yunxiaoAgentToolService.executeTool("grep_code", "{bad json", tempRepoDir).contains("JSON"));
        assertTrue(yunxiaoAgentToolService.executeTool(null, "{}", tempRepoDir).contains("工具名称不能为空"));
        assertTrue(yunxiaoAgentToolService.executeTool("read_file_range",
                "{\"file_path\":\"a.java\",\"start_line\":\"abc\"}", tempRepoDir).contains("异常"));
    }

    /**
     * 测试 list_methods：列出方法名与起止行号；非 Java 文件给出提示
     */
    @Test
    public void testListMethods() throws IOException {
        writeAllocationService();

        String result = yunxiaoAgentToolService.executeTool("list_methods",
                "{\"file_path\":\"src/main/java/com/ct/scm/service/AllocationOrderServiceImpl.java\"}", tempRepoDir);

        assertTrue(result.contains("共 3 个方法"));
        assertTrue(result.contains("- audit (行 2-5)"));
        assertTrue(result.contains("- checkStatus (行 6-8)"));
        assertTrue(yunxiaoAgentToolService.executeTool("list_methods",
                "{\"file_path\":\"src/main/resources/mapper/AllocationOrderMapper.xml\"}", tempRepoDir).contains("get_mapper_sql"));
    }

    /**
     * 测试 find_callers：排除方法声明本身，返回调用位置与所在方法；按 class_name 过滤同名方法
     */
    @Test
    public void testFindCallers() throws IOException {
        writeAllocationService();
        File controllerDir = new File(tempRepoDir, "src/main/java/com/ct/scm/controller");
        controllerDir.mkdirs();
        Files.writeString(new File(controllerDir, "AllocationOrderController.java").toPath(),
                "public class AllocationOrderController {\n"
                        + "    private IAllocationOrderService allocationOrderService;\n"
                        + "    public void audit(Long id) {\n"
                        + "        allocationOrderService.audit(id);\n"
                        + "    }\n"
                        + "}\n", StandardCharsets.UTF_8);
        // 同名方法、但与调拨单无关的类
        Files.writeString(new File(controllerDir, "PurchaseController.java").toPath(),
                "public class PurchaseController {\n"
                        + "    public void run() { purchaseService.audit(1L); }\n"
                        + "}\n", StandardCharsets.UTF_8);

        String checkCallers = yunxiaoAgentToolService.executeTool("find_callers", "{\"method_name\":\"checkStatus\"}", tempRepoDir);
        assertTrue(checkCallers.contains("AllocationOrderServiceImpl.java:3 [audit]"));
        assertFalse(checkCallers.contains(":6"));

        String auditCallers = yunxiaoAgentToolService.executeTool("find_callers",
                "{\"method_name\":\"audit\",\"class_name\":\"AllocationOrderServiceImpl\"}", tempRepoDir);
        assertTrue(auditCallers.contains("AllocationOrderController.java:4 [audit]"));
        assertFalse(auditCallers.contains("PurchaseController"));
        // 方法声明行不算调用
        assertFalse(auditCallers.contains("AllocationOrderController.java:3"));
    }

    private void writeAllocationService() throws IOException {
        File serviceDir = new File(tempRepoDir, "src/main/java/com/ct/scm/service");
        serviceDir.mkdirs();
        Files.writeString(new File(serviceDir, "AllocationOrderServiceImpl.java").toPath(),
                "public class AllocationOrderServiceImpl implements IAllocationOrderService {\n"
                        + "    public void audit(Long id) {\n"
                        + "        checkStatus(id);\n"
                        + "        log.info(\"audit\");\n"
                        + "    }\n"
                        + "    private void checkStatus(Long id) {\n"
                        + "        // checkStatus 不在此处调用\n"
                        + "    }\n"
                        + "    public List<Long> list() { return null; }\n"
                        + "}\n", StandardCharsets.UTF_8);
        File mapperDir = new File(tempRepoDir, "src/main/resources/mapper");
        mapperDir.mkdirs();
        Files.writeString(new File(mapperDir, "AllocationOrderMapper.xml").toPath(), "<mapper/>\n", StandardCharsets.UTF_8);
    }
}
