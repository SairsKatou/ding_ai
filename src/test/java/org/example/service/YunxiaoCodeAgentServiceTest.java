package org.example.service;

import com.ct.common.core.constant.Constants;
import org.apache.commons.lang3.StringUtils;
import org.example.domain.dto.CodeAgentAnalysisDTO;
import org.example.domain.dto.CodeAgentSeedDTO;
import org.example.domain.dto.OpenChatFunctionCallDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatToolCallDTO;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.service.impl.YunxiaoCodeAgentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 云效代码 Agent 多轮 Tool Calling 深度探查单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoCodeAgentServiceTest {

    @InjectMocks
    private YunxiaoCodeAgentServiceImpl yunxiaoCodeAgentService;

    @Mock
    private OpenCodeService openCodeService;

    @Mock
    private YunxiaoAgentToolService yunxiaoAgentToolService;

    @Mock
    private CodeSearchKeywordService codeSearchKeywordService;

    @Mock
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    private File tempRepoDir;

    @BeforeEach
    public void setUp() {
        tempRepoDir = new File(System.getProperty("java.io.tmpdir"), "agent-service-test-" + System.currentTimeMillis());
        tempRepoDir.mkdirs();
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
     * 测试两轮交互：第 1 轮返回 Tool Calling，第 2 轮返回最终分析文本
     */
    @Test
    public void testExecuteDeepScanWithToolCalling() {
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("调拨单");
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());

        // 模拟第 1 轮：大模型发起 tool_call (search_files)
        OpenChatToolCallDTO toolCall = OpenChatToolCallDTO.builder()
                .id("call_001")
                .type("function")
                .function(OpenChatFunctionCallDTO.builder()
                        .name("search_files")
                        .arguments("{\"pattern\":\"Allocation\"}")
                        .build())
                .build();

        OpenChatMessageDTO firstRoundReply = OpenChatMessageDTO.builder()
                .role("assistant")
                .toolCalls(Collections.singletonList(toolCall))
                .build();

        // 模拟第 2 轮：大模型返回最终自然语言总结
        OpenChatMessageDTO secondRoundReply = OpenChatMessageDTO.builder()
                .role("assistant")
                .content("调拨单涉及的核心表为：allocation_order、allocation_order_detail。")
                .build();

        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(firstRoundReply)
                .thenReturn(secondRoundReply);

        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any()))
                .thenReturn("找到文件: AllocationOrder.java");

        YunxiaoCodeScanParam param = YunxiaoCodeScanParam.builder()
                .repositoryId("changtai-cloud")
                .branch("dev")
                .question("调拨单涉及的表名是哪些")
                .deepScan(Boolean.TRUE)
                .build();

        YunxiaoCodeScanResultDTO result = yunxiaoCodeAgentService.executeDeepScan(param, tempRepoDir, "changtai-cloud", "6120951");

        assertNotNull(result);
        assertNotNull(result.getAnalysisReport());
        assertTrue(result.getAnalysisReport().contains("allocation_order"));
    }

    /**
     * 测试逻辑类问题：使用调用链提示词，并在首轮上下文中注入本地预检索线索
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testLogicPromptWithSeed() {
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("实报费用新增");
        when(codeSearchKeywordService.isTableQuestion(anyString())).thenReturn(false);
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(yunxiaoGitCloneService.buildAgentSeed(any(File.class), anyString(), anyString(), anyInt()))
                .thenReturn(CodeAgentSeedDTO.builder()
                        .englishTokens(List.of("reimbursement"))
                        .topFiles(List.of("service/ScmActualReimbursementServiceImpl.java"))
                        .topFilePath("service/ScmActualReimbursementServiceImpl.java")
                        .topFileMethods(List.of("addReimbursement (行 423-484)"))
                        .build());
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("L423: public void addReimbursement() {}");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("call_1", "read_method", "{\"file_path\":\"a.java\",\"method_name\":\"addReimbursement\"}"))
                .thenReturn(textReply("核心逻辑在 addReimbursement。"));

        yunxiaoCodeAgentService.executeDeepScan(buildParam("梳理实报费用新增的校验逻辑"), tempRepoDir, "changtai-cloud", "6120951");

        ArgumentCaptor<List<OpenChatMessageDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(openCodeService, times(Constants.TWO)).chatWithTools(captor.capture(), any());
        List<OpenChatMessageDTO> firstRound = captor.getAllValues().get(Constants.ZERO);
        assertTrue(firstRound.get(Constants.ZERO).getContent().contains("沿调用链"));
        assertFalse(firstRound.get(Constants.ZERO).getContent().contains("表名清单"));
        String userPrompt = firstRound.get(Constants.ONE).getContent();
        assertTrue(userPrompt.contains("本地预检索线索"));
        assertTrue(userPrompt.contains("reimbursement"));
        assertTrue(userPrompt.contains("addReimbursement (行 423-484)"));
    }

    /**
     * 测试大模型始终不调用工具：先追问一次，仍不调用则抛出异常（由调用方降级），且不做汇总
     */
    @Test
    public void testNoToolCallThrows() {
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("调拨单");
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(openCodeService.chatWithTools(anyList(), any())).thenReturn(textReply("凭空编写的结论"));

        assertThrows(IllegalStateException.class, () -> yunxiaoCodeAgentService.executeDeepScan(
                buildParam("调拨单审核逻辑"), tempRepoDir, "changtai-cloud", "6120951"));
        verify(openCodeService, times(Constants.TWO)).chatWithTools(anyList(), any());
        verify(openCodeService, never()).chat(anyList());
    }

    /**
     * 测试相同工具 + 相同参数（key 顺序不同）只执行一次；早期结果在 KEEP_RECENT_TURNS 轮后被压缩
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testDuplicateCallCachedAndOldResultsCompressed() {
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("调拨单");
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("FULL_RESULT_LINE\nmore");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("c1", "read_method", "{\"file_path\":\"a.java\",\"method_name\":\"audit\"}"))
                .thenReturn(toolCallReply("c2", "read_method", "{\"method_name\":\"audit\", \"file_path\":\"a.java\"}"))
                .thenReturn(toolCallReply("c3", "grep_code", "{\"keyword\":\"x\"}"))
                .thenReturn(toolCallReply("c4", "grep_code", "{\"keyword\":\"y\"}"))
                .thenReturn(textReply("结论"));

        yunxiaoCodeAgentService.executeDeepScan(buildParam("调拨单审核逻辑"), tempRepoDir, "changtai-cloud", "6120951");

        // 第 2 轮与第 1 轮参数相同，只执行一次 read_method
        verify(yunxiaoAgentToolService, times(Constants.ONE)).executeTool(eq("read_method"), anyString(), any());

        ArgumentCaptor<List<OpenChatMessageDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(openCodeService, times(Constants.FIVE)).chatWithTools(captor.capture(), any());
        List<OpenChatMessageDTO> lastRound = captor.getAllValues().get(Constants.FOUR);
        String firstToolResult = lastRound.stream().filter(m -> "tool".equals(m.getRole()))
                .findFirst().map(OpenChatMessageDTO::getContent).orElse("");
        assertTrue(firstToolResult.startsWith("[第 1 轮结果已压缩]"));
        assertTrue(firstToolResult.contains("FULL_RESULT_LINE"));
    }

    /**
     * 测试单轮大模型调用超出总时限：停止探查，基于已读取的源码进行汇总
     */
    @Test
    public void testDeadlineStopsLoopAndSummarizes() {
        ReflectionTestUtils.setField(yunxiaoCodeAgentService, "loopTimeoutMillis", 300L);
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("调拨单");
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("源码");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("c1", "grep_code", "{\"keyword\":\"allocation\"}"))
                .thenAnswer(invocation -> {
                    Thread.sleep(Constants.FIVE_THOUSAND);
                    return textReply("不应等到这里");
                });
        when(openCodeService.chat(anyList())).thenReturn("汇总报告");

        long start = System.currentTimeMillis();
        YunxiaoCodeScanResultDTO result = yunxiaoCodeAgentService.executeDeepScan(
                buildParam("调拨单审核逻辑"), tempRepoDir, "changtai-cloud", "6120951");

        assertEquals("汇总报告", result.getAnalysisReport());
        assertTrue(System.currentTimeMillis() - start < Constants.THREE_THOUSAND);
    }

    /**
     * 测试普通扫描 3 轮：前 2 轮提供工具，第 3 轮不提供工具并直接得到结论，无单独汇总调用；读取的文件计入结果
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testAnalyzeWithToolsThreeTurns() throws IOException {
        Path serviceFile = tempRepoDir.toPath().resolve("service/DemoServiceImpl.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, "class DemoServiceImpl {}\n");
        List<OpenChatToolDTO> tools = List.of(OpenChatToolDTO.builder().type("function").build());
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(tools);
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("源码");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("c1", "read_method", "{\"file_path\":\"service/DemoServiceImpl.java\",\"method_name\":\"add\"}"))
                .thenReturn(toolCallReply("c2", "find_callers", "{\"method_name\":\"add\"}"))
                .thenReturn(textReply("最终结论"));

        CodeAgentAnalysisDTO result = yunxiaoCodeAgentService.analyzeWithTools(initialMessages(), tempRepoDir, Constants.THREE);

        assertEquals("最终结论", result.getReport());
        assertEquals(Constants.THREE, result.getTurns());
        assertEquals(List.of("service/DemoServiceImpl.java"), result.getReadFiles());
        ArgumentCaptor<List<OpenChatToolDTO>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(openCodeService, times(Constants.THREE)).chatWithTools(anyList(), toolsCaptor.capture());
        assertEquals(tools, toolsCaptor.getAllValues().get(Constants.ZERO));
        assertEquals(tools, toolsCaptor.getAllValues().get(Constants.ONE));
        assertTrue(toolsCaptor.getAllValues().get(Constants.TWO).isEmpty());
        verify(openCodeService, never()).chat(anyList());
    }

    /**
     * 测试普通扫描：片段足够时第 1 轮直接作答，不追问、不要求必须调用工具
     */
    @Test
    public void testAnalyzeWithToolsDirectAnswer() {
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(openCodeService.chatWithTools(anyList(), any())).thenReturn(textReply("直接结论"));

        CodeAgentAnalysisDTO result = yunxiaoCodeAgentService.analyzeWithTools(initialMessages(), tempRepoDir, Constants.THREE);

        assertEquals("直接结论", result.getReport());
        assertEquals(Constants.ONE, result.getTurns());
        verify(openCodeService, times(Constants.ONE)).chatWithTools(anyList(), any());
    }

    /**
     * 测试普通扫描：第 1 轮即失败且没有任何工具结果时抛出异常，不再重复发送相同上下文汇总
     */
    @Test
    public void testAnalyzeWithToolsFirstTurnFailureThrows() {
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(openCodeService.chatWithTools(anyList(), any())).thenThrow(new RuntimeException("模型全部不可用"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> yunxiaoCodeAgentService.analyzeWithTools(initialMessages(), tempRepoDir, Constants.THREE));
        assertTrue(e.getMessage().contains("模型全部不可用"));
        verify(openCodeService, never()).chat(anyList());
    }

    /**
     * 测试最后一轮（不提供工具）模型把工具调用写成 DSML 文本：先明确告知不再提供工具；
     * 仍输出标记时丢弃该回复并汇总，不把标记当作结论返回
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testLeakedToolMarkupIsNotReturned() {
        String leaked = "<｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke name=\"grep_code\"> "
                + "<｜｜DSML｜｜ parameter name=\"keyword\" string=\"true\">REQUESTED</｜｜DSML｜｜ parameter> </｜｜DSML｜｜ invoke> </｜｜DSML｜｜ calls>";
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("源码");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("c1", "grep_code", "{\"keyword\":\"prepay\"}"))
                .thenReturn(toolCallReply("c2", "grep_code", "{\"keyword\":\"status\"}"))
                .thenReturn(textReply(leaked));
        when(openCodeService.chat(anyList())).thenReturn("可使用已审核状态的采购预付单");

        CodeAgentAnalysisDTO result = yunxiaoCodeAgentService.analyzeWithTools(initialMessages(), tempRepoDir, Constants.THREE);

        assertEquals("可使用已审核状态的采购预付单", result.getReport());
        ArgumentCaptor<List<OpenChatMessageDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(openCodeService, times(Constants.THREE)).chatWithTools(captor.capture(), any());
        List<OpenChatMessageDTO> lastTurn = captor.getAllValues().get(Constants.TWO);
        assertTrue(lastTurn.get(lastTurn.size() - Constants.ONE).getContent().contains("本轮不再提供任何工具"));
        // 泄漏的标记不进入汇总上下文
        ArgumentCaptor<List<OpenChatMessageDTO>> summaryCaptor = ArgumentCaptor.forClass(List.class);
        verify(openCodeService).chat(summaryCaptor.capture());
        assertTrue(summaryCaptor.getValue().stream().noneMatch(m -> StringUtils.contains(m.getContent(), "DSML")));
    }

    /**
     * 测试汇总仍只输出工具调用标记：返回空报告（普通扫描据此改用一次性调用兜底），深度扫描抛出异常降级
     */
    @Test
    public void testLeakedMarkupInSummaryReturnsBlank() {
        String leaked = "<｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke name=\"grep_code\"></｜｜DSML｜｜ invoke> </｜｜DSML｜｜ calls>";
        when(codeSearchKeywordService.resolveCoreKeyword(any(), anyString())).thenReturn("采购预付单");
        when(yunxiaoAgentToolService.getToolDefinitions()).thenReturn(Collections.emptyList());
        when(yunxiaoAgentToolService.executeTool(anyString(), anyString(), any())).thenReturn("源码");
        when(openCodeService.chatWithTools(anyList(), any()))
                .thenReturn(toolCallReply("c1", "grep_code", "{\"keyword\":\"prepay\"}"))
                .thenReturn(textReply(leaked));
        when(openCodeService.chat(anyList())).thenReturn(leaked);

        CodeAgentAnalysisDTO quick = yunxiaoCodeAgentService.analyzeWithTools(initialMessages(), tempRepoDir, Constants.THREE);
        assertTrue(StringUtils.isBlank(quick.getReport()));

        assertThrows(IllegalStateException.class, () -> yunxiaoCodeAgentService.executeDeepScan(
                buildParam("采购应付单可以使用什么状态的采购预付单"), tempRepoDir, "changtai-cloud", "6120951"));
    }

    private List<OpenChatMessageDTO> initialMessages() {
        return List.of(
                OpenChatMessageDTO.builder().role("system").content("系统提示").build(),
                OpenChatMessageDTO.builder().role("user").content("源码片段与问题").build());
    }

    private YunxiaoCodeScanParam buildParam(String question) {
        return YunxiaoCodeScanParam.builder()
                .repositoryId("changtai-cloud")
                .branch("dev")
                .question(question)
                .deepScan(Boolean.TRUE)
                .build();
    }

    private OpenChatMessageDTO toolCallReply(String id, String toolName, String arguments) {
        return OpenChatMessageDTO.builder()
                .role("assistant")
                .toolCalls(Collections.singletonList(OpenChatToolCallDTO.builder()
                        .id(id)
                        .type("function")
                        .function(OpenChatFunctionCallDTO.builder().name(toolName).arguments(arguments).build())
                        .build()))
                .build();
    }

    private OpenChatMessageDTO textReply(String content) {
        return OpenChatMessageDTO.builder().role("assistant").content(content).build();
    }
}
