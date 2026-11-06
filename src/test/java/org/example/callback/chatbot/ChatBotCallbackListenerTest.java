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
import org.junit.jupiter.api.Assertions;
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

    /**
     * 测试帮助菜单返回结构化 Markdown 内容
     */
    @Test
    public void testHelpMenuReturnsMarkdown() {
        Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "help");
        assertNotNull(reply);
        String text = reply.toString();
        Assertions.assertTrue(text.contains("### 💡 您好！我是智能开发助理机器人"));
        Assertions.assertTrue(text.contains("常用指令指南"));
        Assertions.assertTrue(text.contains("changtai-cloud"));
        Assertions.assertTrue(text.contains("分支发布"));
        Assertions.assertTrue(text.contains("取消分支发布"));
    }

	/**
	 * 测试输入「查询 changtai-cloud」命中 DevOps 项目查询，而不是调用大模型
	 */
	@Test
	public void testQueryProjectWithShortCommand() {
		when(yunxiaoDevopsService.formatRepositoriesAndBranchesForChat(eq("changtai-cloud")))
				.thenReturn("项目查询结果: changtai-cloud");

		Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "查询 changtai-cloud");

		assertNotNull(reply);
		Assertions.assertEquals("项目查询结果: changtai-cloud", reply.toString());
		verify(yunxiaoDevopsService).formatRepositoriesAndBranchesForChat("changtai-cloud");
		verify(openCodeService, never()).chat(any(String.class));
	}

	/**
	 * 测试输入「项目列表」列出全部项目
	 */
	@Test
	public void testQueryProjectList() {
		when(yunxiaoDevopsService.formatRepositoriesAndBranchesForChat(eq("")))
				.thenReturn("全部项目列表");

		Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "项目列表");

		assertNotNull(reply);
		Assertions.assertEquals("全部项目列表", reply.toString());
		verify(yunxiaoDevopsService).formatRepositoriesAndBranchesForChat("");
		verify(openCodeService, never()).chat(any(String.class));
	}

    /**
     * 测试输入「发布分支 feature/order-v1」分发至流水线发布处理
     */
    @Test
    public void testReleaseBranchCommandDispatched() {
        when(yunxiaoDevopsService.releaseBranchForChat(eq("发布分支 feature/order-v1")))
                .thenReturn("分支发布流水线已触发");

        Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "发布分支 feature/order-v1");

        assertNotNull(reply);
        Assertions.assertEquals("分支发布流水线已触发", reply.toString());
        verify(yunxiaoDevopsService).releaseBranchForChat("发布分支 feature/order-v1");
        verify(openCodeService, never()).chat(any(String.class));
    }

    /**
     * 测试输入「取消发布 feature/order-v1」分发至取消发布处理
     */
    @Test
    public void testCancelReleaseBranchCommandDispatched() {
        when(yunxiaoDevopsService.cancelReleaseBranchForChat(eq("取消发布 feature/order-v1")))
                .thenReturn("分支发布已成功取消");

        Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "取消发布 feature/order-v1");

        assertNotNull(reply);
        Assertions.assertEquals("分支发布已成功取消", reply.toString());
        verify(yunxiaoDevopsService).cancelReleaseBranchForChat("取消发布 feature/order-v1");
        verify(openCodeService, never()).chat(any(String.class));
    }

    /**
     * 测试输入「流水线分组」分发至分组查询处理
     */
    @Test
    public void testPipelineGroupCommandDispatched() {
        when(yunxiaoDevopsService.listPipelineGroupsForChat(eq("流水线分组 订单")))
                .thenReturn("云效流水线分组列表");

        Object reply = ReflectionTestUtils.invokeMethod(chatBotCallbackListener, "handleUserQuery", "流水线分组 订单");

        assertNotNull(reply);
        Assertions.assertEquals("云效流水线分组列表", reply.toString());
        verify(yunxiaoDevopsService).listPipelineGroupsForChat("流水线分组 订单");
        verify(openCodeService, never()).chat(any(String.class));
    }
}
