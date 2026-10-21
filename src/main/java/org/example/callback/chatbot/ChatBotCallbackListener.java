package org.example.callback.chatbot;

import com.alibaba.fastjson.JSONObject;
import com.ct.common.core.constant.Constants;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.service.DingTalkMessageDeduplicatorService;
import org.example.service.OpenCodeService;
import org.example.service.RobotGroupMessagesService;
import org.example.service.YunxiaoCodeScanService;
import org.example.service.YunxiaoDevopsService;
import org.example.service.YunxiaoGitCloneService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 钉钉机器人群聊消息回调监听器
 *
 * @author zeymo
 */
@Slf4j
@Component
public class ChatBotCallbackListener implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {

    @Resource
    private RobotGroupMessagesService robotGroupMessagesService;

    @Resource
    private YunxiaoDevopsService yunxiaoDevopsService;

    @Resource
    private YunxiaoCodeScanService yunxiaoCodeScanService;

    @Resource
    private YunxiaoGitCloneService yunxiaoGitCloneService;

    @Resource
    private OpenCodeService openCodeService;

    @Resource
    private DingTalkMessageDeduplicatorService dingTalkMessageDeduplicatorService;

    @Resource(name = "botMessageExecutor")
    private ThreadPoolTaskExecutor botMessageExecutor;

    /**
     * 处理钉钉群内 @机器人的事件消息
     * 接口文档参考：https://open.dingtalk.com/document/orgapp/the-application-robot-in-the-enterprise-sends-group-chat-messages
     *
     * @param message 钉钉机器人群聊消息
     * @return 处理结果
     */
    @Override
    public JSONObject execute(ChatbotMessage message) {
        if (message == null) {
            return new JSONObject();
        }

        String msgId = message.getMsgId();
        // 1. 消息幂等防重拦截（防止网络重试或多次投递）
        if (dingTalkMessageDeduplicatorService.isDuplicate(msgId)) {
            log.warn("检测到重复推送的钉钉消息 msgId: [{}], 予以拦截忽略", msgId);
            return new JSONObject();
        }

        MessageContent text = message.getText();
        if (text == null || StringUtils.isBlank(text.getContent())) {
            return new JSONObject();
        }

        String rawMsg = text.getContent();
        String senderStaffId = message.getSenderStaffId();
        String openConversationId = message.getConversationId();

        // 2. 立即提交至线程池异步处理，避免同步耗时操作触发钉钉服务端 3-5 秒超时重推机制
        botMessageExecutor.execute(() -> processMessageAsync(rawMsg, senderStaffId, openConversationId));

        // 3. 毫秒级即时应答 ACK
        return new JSONObject();
    }

    /**
     * 异步处理用户消息并回复钉钉群
     *
     * @param rawMsg             原始消息文本
     * @param senderStaffId      发送人用户ID
     * @param openConversationId 钉钉群会话ID
     */
    private void processMessageAsync(String rawMsg, String senderStaffId, String openConversationId) {
        try {
            log.info("异步开始处理群聊机器人消息, 发送人: {}, 内容: {}", senderStaffId, rawMsg);
            // 剔除 @机器人 前缀名称（例如 @项目逻辑查询 等）
            String userQuestion = rawMsg.replaceAll("@\\S+\\s*", "").trim();
            log.info("提取用户输入内容: {}", userQuestion);

            String result = handleUserQuery(userQuestion);
            log.info("机器人回复内容: {}", result);

            // 发送群消息，传入会话 ID、需要 @ 的人、回复文本
            robotGroupMessagesService.send(openConversationId, senderStaffId, result);
        } catch (Exception e) {
            log.error("异步处理钉钉机器人消息异常: " + e.getMessage(), e);
        }
    }

    /**
     * 根据用户输入分发处理业务或 AI 智能回复
     *
     * @param userQuestion 用户提问内容
     * @return 业务回复结果
     */
    private String handleUserQuery(String userQuestion) {
        if (StringUtils.isBlank(userQuestion) || userQuestion.equalsIgnoreCase("help")
                || userQuestion.equals("帮助") || userQuestion.equals("菜单")) {
            return "💡 您好！我是智能开发助理机器人：\n"
                    + "👉 查询分支：输入「项目列表」或「查询 <项目名>」\n"
                    + "👉 创建分支：输入「创建分支 <仓库名> <新分支名> [源分支]」\n"
                    + "👉 克隆分支：输入「克隆分支 <仓库名> [分支名/不填默认dev]」（先删历史再拉最新，锁定2小时）\n"
                    + "👉 分支代码扫描分析：输入「扫描项目 <项目名> 分支 <分支名> <具体诉求>」\n"
                    + "   （示例：`扫描项目 changtai-cloud 分支 dev 梳理实报使用的地方`）\n"
                    + "👉 OpenCode 大模型问答：直接输入任何技术或代码问题即可获得解答！";
        }

        // 1. 分支代码扫描与大模型分析指令
        if (userQuestion.startsWith("扫描")
                || userQuestion.startsWith("分析分支")
                || userQuestion.startsWith("代码扫描")
                || userQuestion.startsWith("审计分支")) {
            return yunxiaoCodeScanService.scanAndAnalyzeForChat(userQuestion);
        }

        // 2. 克隆分支指令（不指定分支默认 dev，先删历史后新增）
        if (userQuestion.startsWith("克隆分支")
                || userQuestion.startsWith("克隆项目")
                || userQuestion.startsWith("克隆代码")
                || userQuestion.startsWith("重新克隆")
                || userQuestion.startsWith("拉取分支")) {
            return yunxiaoGitCloneService.cloneBranchForChat(userQuestion);
        }

        // 3. 创建分支指令
        if (userQuestion.startsWith("创建分支")
                || userQuestion.startsWith("新建分支")
                || userQuestion.startsWith("拉分支")) {
            return yunxiaoDevopsService.createBranchForChat(userQuestion);
        }

        // 3. 项目与分支查询指令
        boolean isDevopsQuery = userQuestion.equals("项目列表")
                || userQuestion.equals("分支列表")
                || userQuestion.startsWith("查询项目")
                || userQuestion.startsWith("搜索项目")
                || userQuestion.startsWith("云效项目")
                || userQuestion.contains("代码库")
                || userQuestion.startsWith("查询分支");

        if (isDevopsQuery) {
            String searchKeyword = userQuestion
                    .replaceAll("^(查询|搜索|获取|查看|云效|项目|分支|仓库|代码库|列表)+", "")
                    .trim();
            return yunxiaoDevopsService.formatRepositoriesAndBranchesForChat(searchKeyword);
        }

        // 3. 显式带有 ai 前缀
        String prompt = userQuestion;
        if (userQuestion.toLowerCase().startsWith("ai ") || userQuestion.toLowerCase().startsWith("ai：")) {
            prompt = userQuestion.substring(Constants.THREE).trim();
        }

        // 4. 其余所有问题默认交由 OpenCode 大模型回答
        try {
            return openCodeService.chat(prompt);
        } catch (Exception e) {
            log.error("大模型对话处理异常: " + e.getMessage(), e);
            return "❌ AI 助手回答异常: " + e.getMessage();
        }
    }
}
