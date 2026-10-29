package org.example.service;

import com.ct.common.core.constant.Constants;
import org.example.domain.dto.CodeSearchTermsDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.service.impl.CodeSearchKeywordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 代码扫描关键词提炼与搜索 Token 构建服务单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class CodeSearchKeywordServiceTest {

    @InjectMocks
    private CodeSearchKeywordServiceImpl codeSearchKeywordService;

    @Mock
    private OpenCodeService openCodeService;

    @BeforeEach
    public void setUp() {
    }

    /**
     * 测试从自然语言问句中智能提炼核心业务词
     */
    @Test
    public void testResolveCoreKeyword() {
        // 测试提炼“调拨单涉及的表名是哪些”中的核心词
        String keyword1 = codeSearchKeywordService.resolveCoreKeyword(null, "调拨单涉及的表名是哪些");
        assertEquals("调拨单", keyword1);

        // 测试提炼“请问调拨单涉及哪些表？”
        String keyword2 = codeSearchKeywordService.resolveCoreKeyword("", "请问调拨单涉及哪些表？");
        assertEquals("调拨单", keyword2);

        // 测试提炼“梳理实报使用的地方”
        String keyword3 = codeSearchKeywordService.resolveCoreKeyword(null, "梳理实报使用的地方");
        assertEquals("实报", keyword3);

        // 测试提炼“采购订单有哪些状态”
        String keyword4 = codeSearchKeywordService.resolveCoreKeyword(null, "采购订单有哪些状态");
        assertEquals("采购订单", keyword4);

        // 测试显式指定关键词时优先使用
        String keyword5 = codeSearchKeywordService.resolveCoreKeyword("allocation", "调拨单涉及的表名是哪些");
        assertEquals("allocation", keyword5);

        // 测试提炼“关于实报费用有几张表”
        String keyword6 = codeSearchKeywordService.resolveCoreKeyword(null, "关于实报费用有几张表");
        assertEquals("实报费用", keyword6);

        // 测试提炼“对账单明细拆分逻辑”，并覆盖 spilt 拼写
        String keyword7 = codeSearchKeywordService.resolveCoreKeyword(null, "对账单明细拆分逻辑");
        assertEquals("对账单明细拆分", keyword7);
        Set<String> tokens = codeSearchKeywordService.buildSearchTokens(keyword7, keyword7);
        assertTrue(tokens.contains("拆分"));
        assertTrue(tokens.contains("spilt"));
        assertTrue(tokens.contains("statement_account"));
    }

    /**
     * 测试根据提炼后的关键词构建多粒度中文与英文搜索 Token
     */
    @Test
    public void testBuildSearchTokensForAllocation() {
        // 构建“调拨单”相关 Token
        Set<String> tokens = codeSearchKeywordService.buildSearchTokens("调拨单", "调拨单涉及的表名是哪些");

        assertNotNull(tokens);
        assertFalse(tokens.isEmpty());

        // 验证包含中文业务词及词根
        assertTrue(tokens.contains("调拨单"));
        assertTrue(tokens.contains("调拨"));

        // 验证包含行业英文映射
        assertTrue(tokens.contains("allocation"));
        assertTrue(tokens.contains("allocationorder"));
        assertTrue(tokens.contains("allocation_order"));
        assertTrue(tokens.contains("transfer"));
    }

    /**
     * 测试根据实报提炼 Token
     */
    @Test
    public void testBuildSearchTokensForActualReport() {
        // 构建“实报”相关 Token
        Set<String> tokens = codeSearchKeywordService.buildSearchTokens("实报", "梳理实报使用的地方");

        assertNotNull(tokens);
        assertTrue(tokens.contains("实报"));
        assertTrue(tokens.contains("actual"));
        assertTrue(tokens.contains("reimbursement"));
    }

    /**
     * 测试当未命中文件时大模型推断英文 Token 的兜底机制
     */
    @Test
    public void testInferEnglishTokensWithAI() {
        when(openCodeService.chat(anyList())).thenReturn("allocation, order, diaobo_record");

        Set<String> aiTokens = codeSearchKeywordService.inferEnglishTokensWithAI("调拨单涉及的表名是哪些");

        assertNotNull(aiTokens);
        assertTrue(aiTokens.contains("allocation"));
        assertTrue(aiTokens.contains("order"));
        assertTrue(aiTokens.contains("diaobo_record"));
    }

    /**
     * 测试大模型推断检索词时区分实体词与动作词
     */
    @Test
    public void testInferSearchTermsWithAI() {
        when(openCodeService.chat(anyList())).thenReturn("实体: purchase_return, purchasereturn\n动作：audit,approve");

        CodeSearchTermsDTO terms = codeSearchKeywordService.inferSearchTermsWithAI("采购退货审核逻辑");

        assertEquals(Set.of("purchase_return", "purchasereturn"), terms.getEntityTokens());
        assertEquals(Set.of("audit", "approve"), terms.getActionTokens());
    }

    /**
     * 测试动作词提取，以及去掉动作词后的实体中文词
     */
    @Test
    public void testActionTokensAndEntityKeyword() {
        Set<String> actions = codeSearchKeywordService.resolveActionTokens("梳理实报费用新增的校验逻辑");
        assertTrue(actions.contains("add"));
        assertTrue(actions.contains("check"));
        assertTrue(actions.contains("validate"));

        String keyword = codeSearchKeywordService.resolveCoreKeyword(null, "梳理实报费用新增的校验逻辑");
        assertEquals("实报费用新增", keyword);
        Set<String> tokens = codeSearchKeywordService.buildSearchTokens(keyword, "梳理实报费用新增的校验逻辑");
        assertTrue(tokens.contains("实报费用"));
        assertTrue(tokens.contains("reimbursement"));

        assertTrue(codeSearchKeywordService.hasEnglishToken("调拨单", "调拨单审核"));
        assertFalse(codeSearchKeywordService.hasEnglishToken("售后工单", "售后工单审核逻辑"));
    }
}
