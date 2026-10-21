package org.example.callback.chatbot;

import com.alibaba.fastjson.JSONObject;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import org.example.service.DingTalkMessageDeduplicatorService;
import org.example.service.OpenCodeService;
import org.example.service.RobotGroupMessagesService;
import org.example.service.YunxiaoCodeScanService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉钉群聊消息监听器单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class ChatBotCallbackListenerTest {

    @InjectMocks
    private ChatBotCallbackListener chatBotCallbackListener;

    @Mock
    private DingTalkMessageDeduplicatorService dingTalkMessageDeduplicatorService;

    @Mock
    private ThreadPoolTaskExecutor botMessageExecutor;

    @Mock
    private RobotGroupMessagesService robotGroupMessagesService;

    @Mock
    private YunxiaoDevopsService yunxiaoDevopsService;

    @Mock
    private YunxiaoCodeScanService yunxiaoCodeScanService;

    @Mock
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    @Mock
    private OpenCodeService openCodeService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(chatBotCallbackListener, "dingTalkMessageDeduplicatorService", dingTalkMessageDeduplicatorService);
        ReflectionTestUtils.setField(chatBotCallbackListener, "botMessageExecutor", botMessageExecutor);
        ReflectionTestUtils.setField(chatBotCallbackListener, "robotGroupMessagesService", robotGroupMessagesService);
        ReflectionTestUtils.setField(chatBotCallbackListener, "yunxiaoDevopsService", yunxiaoDevopsService);
        ReflectionTestUtils.setField(chatBotCallbackListener, "yunxiaoCodeScanService", yunxiaoCodeScanService);
        ReflectionTestUtils.setField(chatBotCallbackListener, "yunxiaoGitCloneService", yunxiaoGitCloneService);
        ReflectionTestUtils.setField(chatBotCallbackListener, "openCodeService", openCodeService);
    }

    /**
     * 测试重复消息被防重拦截
     */
    @Test
    public void testDuplicateMessageBlocked() {
        ChatbotMessage message = new ChatbotMessage();
        message.setMsgId("duplicate_msg_001");
        MessageContent content = new MessageContent();
        content.setContent("代码扫描 changtai-cloud dev 梳理实报");
        message.setText(content);

        when(dingTalkMessageDeduplicatorService.isDuplicate(eq("duplicate_msg_001"))).thenReturn(true);

        JSONObject result = chatBotCallbackListener.execute(message);

        assertNotNull(result);
        verify(botMessageExecutor, never()).execute(any(Runnable.class));
    }

    /**
     * 测试首次新消息异步提交并立即 ACK
     */
    @Test
    public void testNewMessageAsyncDispatched() {
        ChatbotMessage message = new ChatbotMessage();
        message.setMsgId("new_msg_001");
        message.setConversationId("cid123");
        message.setSenderStaffId("user001");
        MessageContent content = new MessageContent();
        content.setContent("代码扫描 changtai-cloud 分支 dev 梳理实报费用新增的校验逻辑");
        message.setText(content);

        when(dingTalkMessageDeduplicatorService.isDuplicate(eq("new_msg_001"))).thenReturn(false);

        JSONObject result = chatBotCallbackListener.execute(message);

        assertNotNull(result);
        // 验证已提交异步线程池执行
        verify(botMessageExecutor).execute(any(Runnable.class));
    }
}
