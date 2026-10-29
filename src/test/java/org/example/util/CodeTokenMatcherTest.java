package org.example.util;

import com.ct.common.core.constant.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码检索 Token 词边界匹配单元测试
 *
 * @author zeymo
 */
public class CodeTokenMatcherTest {

    /**
     * 测试短泛化词按词边界匹配：log 命中 log.info / OperationLog / operation_log，不命中 catalog / dialog / logistics
     */
    @Test
    public void testShortTokenBoundary() {
        assertTrue(CodeTokenMatcher.contains("log.info(\"x\")", "log"));
        assertTrue(CodeTokenMatcher.contains("private OperationLog operationLog;", "log"));
        assertTrue(CodeTokenMatcher.contains("sys_operation_log", "log"));
        assertTrue(CodeTokenMatcher.contains("List<String> logs", "log"));

        // Logger 在每个类中都有，不作为「日志」业务命中
        assertFalse(CodeTokenMatcher.contains("private static final Logger LOGGER", "log"));
        assertFalse(CodeTokenMatcher.contains("CatalogService catalog", "log"));
        assertFalse(CodeTokenMatcher.contains("showDialog()", "log"));
        assertFalse(CodeTokenMatcher.contains("LogisticsProvider", "log"));
    }

    /**
     * 测试驼峰、连写与屈折后缀
     */
    @Test
    public void testCamelCaseAndInflection() {
        assertTrue(CodeTokenMatcher.contains("ScmActualReimbursementController.java", "reimbursement"));
        assertTrue(CodeTokenMatcher.contains("ScmActualReimbursementController.java", "actualreimbursement"));
        assertTrue(CodeTokenMatcher.contains("List<AllocationOrderDetail> details", "allocationorder"));
        assertTrue(CodeTokenMatcher.contains("saveSpiltDetails()", "detail"));
        assertTrue(CodeTokenMatcher.contains("SCMOrder", "order"));
        assertTrue(CodeTokenMatcher.contains("isAdded", "add"));

        assertFalse(CodeTokenMatcher.contains("address", "add"));
        assertFalse(CodeTokenMatcher.contains("orderly", "order"));
    }

    /**
     * 测试中文 Token 按子串匹配，以及计数
     */
    @Test
    public void testChineseTokenAndCount() {
        assertTrue(CodeTokenMatcher.contains("// 实报费用新增", "实报"));
        assertEquals(Constants.TWO, CodeTokenMatcher.countMatches("log.info(a); catalog; log.warn(b);", "log"));
        assertEquals(Constants.ZERO, CodeTokenMatcher.countMatches("", "log"));
    }
}
