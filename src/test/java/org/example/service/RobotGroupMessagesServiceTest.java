package org.example.service;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉钉机器人群消息服务单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class RobotGroupMessagesServiceTest {

    @InjectMocks
    private RobotGroupMessagesService robotGroupMessagesService;

    @Mock
    private Client robotClient;

    @Mock
    private AccessTokenService accessTokenService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(robotGroupMessagesService, "robotClient", robotClient);
        ReflectionTestUtils.setField(robotGroupMessagesService, "accessTokenService", accessTokenService);
        ReflectionTestUtils.setField(robotGroupMessagesService, "robotCode", "test-robot-code");
    }

    /**
     * 测试以 Markdown 格式发送群消息
     */
    @Test
    public void testSendMarkdownMessage() throws Exception {
        when(accessTokenService.getAccessToken()).thenReturn("test-access-token");

        OrgGroupSendResponse mockResponse = new OrgGroupSendResponse();
        OrgGroupSendResponseBody body = new OrgGroupSendResponseBody();
        body.setProcessQueryKey("query-key-001");
        mockResponse.setBody(body);

        when(robotClient.orgGroupSendWithOptions(any(OrgGroupSendRequest.class), any(OrgGroupSendHeaders.class), any(RuntimeOptions.class)))
                .thenReturn(mockResponse);

        String markdownContent = "### 审核结果\n- 状态: 正常\n- 说明: 验证通过";
        String result = robotGroupMessagesService.send("cid-12345", "staff-001", markdownContent);

        assertNotNull(result);
        assertEquals("query-key-001", result);

        // 验证请求参数
        ArgumentCaptor<OrgGroupSendRequest> requestCaptor = ArgumentCaptor.forClass(OrgGroupSendRequest.class);
        verify(robotClient).orgGroupSendWithOptions(requestCaptor.capture(), any(OrgGroupSendHeaders.class), any(RuntimeOptions.class));

        OrgGroupSendRequest capturedRequest = requestCaptor.getValue();
        assertEquals("sampleMarkdown", capturedRequest.getMsgKey());
        assertEquals("test-robot-code", capturedRequest.getRobotCode());
        assertEquals("cid-12345", capturedRequest.getOpenConversationId());

        JSONObject param = JSONObject.parseObject(capturedRequest.getMsgParam());
        assertNotNull(param);
        assertEquals("审核结果", param.getString("title"));
        assertEquals(markdownContent, param.getString("text"));
        assertTrue(param.getJSONArray("atUserIds").contains("staff-001"));
    }
}
