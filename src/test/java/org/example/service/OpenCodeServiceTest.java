package org.example.service;

import com.ct.common.core.constant.Constants;
import org.example.config.OpenCodeModelItem;
import org.example.config.OpenCodeProperties;
import org.example.service.impl.OpenCodeServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode 大模型服务单元测试（多模型轮询与故障转移）
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class OpenCodeServiceTest {

    @Mock
    private RestTemplate openCodeRestTemplate;

    @Mock
    private OpenCodeProperties openCodeProperties;

    @InjectMocks
    private OpenCodeServiceImpl openCodeService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(openCodeService, "openCodeRestTemplate", openCodeRestTemplate);
        ReflectionTestUtils.setField(openCodeService, "openCodeProperties", openCodeProperties);
    }

    /**
     * 测试单模型正常调用成功
     */
    @Test
    public void testChatSuccess() {
        setupMockProperties();

        String mockResponseJson = "{"
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"二分查找的实现如下...\"}"
                + "}]"
                + "}";

        Mockito.when(openCodeRestTemplate.exchange(
                Mockito.eq("https://opencode.ai/inference/openai/v1/chat/completions"),
                Mockito.eq(HttpMethod.POST),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenReturn(ResponseEntity.ok(mockResponseJson));

        String reply = openCodeService.chat("写个二分查找");
        Assertions.assertEquals("二分查找的实现如下...", reply);
    }

    /**
     * 测试单模型失败3次后，成功切换到第2个模型
     */
    @Test
    public void testModelFailoverSuccess() {
        setupMockProperties();

        String successJson = "{"
                + "\"choices\":[{"
                + "\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"模型2回复成功\"}"
                + "}]"
                + "}";

        // 模型1失败3次，第4次调用（模型2的第1次尝试）成功
        Mockito.when(openCodeRestTemplate.exchange(
                Mockito.eq("https://opencode.ai/inference/openai/v1/chat/completions"),
                Mockito.eq(HttpMethod.POST),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        ))
        .thenThrow(new RestClientException("模型1超时第1次"))
        .thenThrow(new RestClientException("模型1超时第2次"))
        .thenThrow(new RestClientException("模型1超时第3次"))
        .thenReturn(ResponseEntity.ok(successJson));

        String reply = openCodeService.chat("你好");
        Assertions.assertEquals("模型2回复成功", reply);

        // 验证累计调用了 4 次 (模型1尝试3次 + 模型2尝试1次)
        Mockito.verify(openCodeRestTemplate, Mockito.times(4)).exchange(
                Mockito.anyString(), Mockito.eq(HttpMethod.POST), Mockito.any(), Mockito.eq(String.class));
    }

    /**
     * 测试3个模型各失败3次（累计9次失败），最终抛出异常
     */
    @Test
    public void testAllNineRetriesFailThrowsException() {
        setupMockProperties();

        // 每次调用均抛出网络或服务异常
        Mockito.when(openCodeRestTemplate.exchange(
                Mockito.eq("https://opencode.ai/inference/openai/v1/chat/completions"),
                Mockito.eq(HttpMethod.POST),
                Mockito.any(HttpEntity.class),
                Mockito.eq(String.class)
        )).thenThrow(new RestClientException("连接远程服务失败"));

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> {
            openCodeService.chat("测试高并发重试失败");
        });

        // 验证异常信息包含重试 9 次及 3 个模型说明
        Assertions.assertTrue(exception.getMessage().contains("3 个模型"));
        Assertions.assertTrue(exception.getMessage().contains("9 次"));

        // 验证累计调用正好是 9 次 (3个模型 * 3次)
        Mockito.verify(openCodeRestTemplate, Mockito.times(9)).exchange(
                Mockito.anyString(), Mockito.eq(HttpMethod.POST), Mockito.any(), Mockito.eq(String.class));
    }

    /**
     * 设置多模型测试参数 (共配置 3 个候选模型)
     */
    private void setupMockProperties() {
        Mockito.when(openCodeProperties.getEnabled()).thenReturn(true);
        Mockito.when(openCodeProperties.getApiKey()).thenReturn("sk-mock-key");
        Mockito.when(openCodeProperties.getApiUrl()).thenReturn("https://opencode.ai/inference/openai/v1/chat/completions");
        Mockito.when(openCodeProperties.getTemperature()).thenReturn(0.7);
        Mockito.when(openCodeProperties.getMaxTokens()).thenReturn(2048);
        Mockito.when(openCodeProperties.getSystemPrompt()).thenReturn("你是助手");
        Mockito.when(openCodeProperties.getMaxRetriesPerModel()).thenReturn(Constants.THREE);
        Mockito.when(openCodeProperties.getMaxModelSwitchCount()).thenReturn(Constants.THREE);

        List<OpenCodeModelItem> modelList = new ArrayList<>();
        modelList.add(OpenCodeModelItem.builder().model("kimi-k2.6").build());
        modelList.add(OpenCodeModelItem.builder().model("qwen-2.5-coder-32b").build());
        modelList.add(OpenCodeModelItem.builder().model("deepseek-chat").build());

        Mockito.when(openCodeProperties.getEffectiveModelList()).thenReturn(modelList);
    }
}
