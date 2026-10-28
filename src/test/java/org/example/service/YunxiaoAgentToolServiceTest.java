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
}
