package org.example.service;

import com.ct.common.core.constant.Constants;
import org.example.domain.dto.OpenChatFunctionCallDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatToolCallDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;
import org.example.service.impl.YunxiaoCodeAgentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
}
