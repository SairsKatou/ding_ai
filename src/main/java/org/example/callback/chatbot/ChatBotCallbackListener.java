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
		// 判断是否为帮助指令
		if (isHelpQuery(userQuestion)) {
			return buildHelpMenu();
		}

		String trimmedQuery = userQuestion.trim();

		// 1. 分支代码扫描与大模型分析指令 (支持普通扫描与 Agent 多轮深度探查)
		if (isCodeScanQuery(trimmedQuery)) {
			return yunxiaoCodeScanService.scanAndAnalyzeForChat(trimmedQuery);
		}

		// 2. 克隆分支指令（不指定分支默认 dev，先删历史后新增）
		if (isGitCloneQuery(trimmedQuery)) {
			return yunxiaoGitCloneService.cloneBranchForChat(trimmedQuery);
		}

		// 3. 取消分支发布指令（终止云效 Flow 流水线运行）
		if (isCancelReleaseQuery(trimmedQuery)) {
			// 解析钉钉群聊取消分支发布指令并执行取消
			return yunxiaoDevopsService.cancelReleaseBranchForChat(trimmedQuery);
		}

		// 4. 分支发布指令（触发云效 Flow 流水线构建发布）
		if (isBranchReleaseQuery(trimmedQuery)) {
			// 解析钉钉群聊分支发布指令并触发流水线发布
			return yunxiaoDevopsService.releaseBranchForChat(trimmedQuery);
		}

		// 5. 流水线分组查询指令
		if (isPipelineGroupQuery(trimmedQuery)) {
			return yunxiaoDevopsService.listPipelineGroupsForChat(trimmedQuery);
		}

		// 6. 创建分支指令
		if (isBranchCreateQuery(trimmedQuery)) {
			return yunxiaoDevopsService.createBranchForChat(trimmedQuery);
		}

		// 7. 项目与分支查询指令
		if (isDevopsQuery(trimmedQuery)) {
			String searchKeyword = resolveDevopsSearchKeyword(trimmedQuery);
			return yunxiaoDevopsService.formatRepositoriesAndBranchesForChat(searchKeyword);
		}

		// 8. 其余问题默认交由 OpenCode 大模型回答
		return executeAiChat(trimmedQuery);
	}

	/**
	 * 判断是否为帮助菜单查询指令
	 *
	 * @param text 用户输入
	 * @return 是否为帮助指令
	 */
	private boolean isHelpQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return true;
		}
		return "help".equalsIgnoreCase(text)
				|| "/help".equalsIgnoreCase(text)
				|| "帮助".equals(text)
				|| "菜单".equals(text)
				|| "?".equals(text)
				|| "？".equals(text);
	}

	/**
	 * 判断是否为分支代码扫描或大模型分析指令
	 *
	 * @param text 用户输入
	 * @return 是否为代码扫描指令
	 */
	private boolean isCodeScanQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		return text.startsWith("扫描")
				|| text.startsWith("深度扫描")
				|| text.startsWith("深度代码扫描")
				|| text.startsWith("代码深度扫描")
				|| text.startsWith("分析分支")
				|| text.startsWith("代码扫描")
				|| text.startsWith("扫描代码")
				|| text.startsWith("分支扫描")
				|| text.startsWith("审计分支");
	}

	/**
	 * 判断是否为克隆分支指令
	 *
	 * @param text 用户输入
	 * @return 是否为克隆指令
	 */
	private boolean isGitCloneQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		return text.startsWith("克隆分支")
				|| text.startsWith("克隆项目")
				|| text.startsWith("克隆代码")
				|| text.startsWith("重新克隆")
				|| text.startsWith("拉取分支");
	}

	/**
	 * 判断是否为取消分支发布指令
	 *
	 * @param text 用户输入
	 * @return 是否为取消分支发布指令
	 */
	private boolean isCancelReleaseQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		// 匹配以取消发布、取消分支发布、停止发布等关键词开头的指令
		return text.startsWith("取消发布分支")
				|| text.startsWith("取消分支发布")
				|| text.startsWith("取消发布")
				|| text.startsWith("停止发布")
				|| text.startsWith("中止发布")
				|| text.startsWith("终止发布")
				|| text.startsWith("撤销发布")
				|| text.startsWith("取消部署")
				|| text.startsWith("停止部署");
	}

	/**
	 * 判断是否为分支发布或流水线部署指令
	 *
	 * @param text 用户输入
	 * @return 是否为分支发布指令
	 */
	private boolean isBranchReleaseQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		// 匹配以发布分支、分支发布、发布代码等关键词开头的指令
		return text.startsWith("发布分支")
				|| text.startsWith("分支发布")
				|| text.startsWith("发布代码")
				|| text.startsWith("代码发布")
				|| text.startsWith("部署分支")
				|| text.startsWith("分支部署")
				|| text.startsWith("部署代码")
				|| text.startsWith("发布 ")
				|| text.startsWith("发布:")
				|| text.startsWith("发布：")
				|| text.startsWith("部署 ")
				|| text.startsWith("部署:")
				|| text.startsWith("部署：")
				|| "发布分支".equals(text)
				|| "分支发布".equals(text);
	}

	/**
	 * 判断是否为创建分支指令
	 *
	 * @param text 用户输入
	 * @return 是否为创建分支指令
	 */
	private boolean isBranchCreateQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		return text.startsWith("创建分支")
				|| text.startsWith("新建分支")
				|| text.startsWith("拉分支");
	}

	/**
	 * 判断是否为云效流水线分组查询指令
	 *
	 * @param text 用户输入
	 * @return 是否为流水线分组查询指令
	 */
	private boolean isPipelineGroupQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}
		return text.startsWith("流水线分组")
				|| text.startsWith("流水线组")
				|| text.startsWith("分组列表")
				|| text.startsWith("查询分组")
				|| text.startsWith("查询流水线分组");
	}

	/**
	 * 判断是否为云效代码库或分支查询指令
	 *
	 * @param text 用户输入
	 * @return 是否为项目或分支查询指令
	 */
	private boolean isDevopsQuery(String text) {
		if (StringUtils.isBlank(text)) {
			return false;
		}

		// 1. 固定指令词全等匹配
		if (text.equals("项目列表") || text.equals("分支列表")
				|| text.equals("仓库列表") || text.equals("代码库列表")
				|| text.equals("查询项目") || text.equals("查询仓库")) {
			return true;
		}

		// 2. 显式带有项目/仓库/分支/代码库限定词的前缀匹配
		if (text.startsWith("查询项目") || text.startsWith("搜索项目")
				|| text.startsWith("云效项目") || text.startsWith("查询仓库")
				|| text.startsWith("搜索仓库") || text.startsWith("查询分支")
				|| text.startsWith("搜索分支") || text.contains("代码库")) {
			return true;
		}

		// 3. 兼容简写格式：如「查询 changtai-cloud」或「搜索 changtai-cloud」
		if (text.startsWith("查询") || text.startsWith("搜索")) {
			String target = text.replaceFirst("^(?:查询|搜索)[\\s:：]*", "").trim();
			// 如果仅输入“查询/搜索”或目标为标准项目名标识符 (如 changtai-cloud)，则认定为项目查询
			return StringUtils.isBlank(target) || target.matches("^[a-zA-Z0-9_\\-\\.]+$");
		}

		return false;
	}

	/**
	 * 从项目或分支查询输入文本中提炼检索关键字
	 *
	 * @param text 用户输入
	 * @return 过滤后的搜索关键词
	 */
	private String resolveDevopsSearchKeyword(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}
		return text.replaceAll("^(查询|搜索|获取|查看|云效|项目|分支|仓库|代码库|列表)+", "").trim();
	}

	/**
	 * 调用 OpenCode 大模型进行智能问答
	 *
	 * @param userQuestion 用户原始提问内容
	 * @return 大模型回复结果
	 */
	private String executeAiChat(String userQuestion) {
		String prompt = userQuestion;
		// 剥离显式 AI 前缀（如 "ai " 或 "ai："）
		if (userQuestion.toLowerCase().startsWith("ai ") || userQuestion.toLowerCase().startsWith("ai：")) {
			prompt = userQuestion.substring(Constants.THREE).trim();
		}

		try {
			return openCodeService.chat(prompt);
		} catch (Exception e) {
			log.error("大模型对话处理异常: " + e.getMessage(), e);
			return "❌ AI 助手回答异常: " + e.getMessage();
		}
	}

	/**
	 * 构建 Markdown 格式的群聊机器人帮助菜单
	 *
	 * @return 帮助菜单 Markdown 文本
	 */
	private String buildHelpMenu() {
		return "### 💡 您好！我是智能开发助理机器人\n\n"
				+ "> 随时为您提供云效代码库检索、分支管理、流水线发布与大模型智能代码审计支持。\n\n"
				+ "---\n\n"
				+ "#### 📌 常用指令指南\n\n"
				+ "1. **📁 项目与分支查询**\n"
				+ "   - 指令格式：`项目列表` 或 `查询 <项目名>`（亦支持 `查询项目 <项目名>`）\n"
				+ "   - 示例 1（查看所有项目）：`项目列表`\n"
				+ "   - 示例 2（检索指定项目）：`查询 changtai-cloud`\n\n"
				+ "2. **🌿 创建代码分支**\n"
				+ "   - 指令格式：`创建分支 [仓库名/默认changtai-cloud] <新分支名> [源分支/默认dev]`\n"
				+ "   - 示例 1（默认项目与源分支）：`创建分支 feature/order-v1`\n"
				+ "   - 示例 2（指定源分支）：`创建分支 feature/order-v1 dev`\n"
				+ "   - 示例 3（完整指定）：`创建分支 changtai-cloud feature/order-v1 dev`\n\n"
				+ "3. **🚀 分支发布（触发云效 Flow 流水线）**\n"
				+ "   - 指令格式：`发布分支 [分组/仓库名] <待发布分支名> [环境/默认test] [流水线ID/可选]`\n"
				+ "   - 说明：触发云效 Flow 流水线自动化构建部署，支持按分组或环境调度，实时返回批次ID与状态\n"
				+ "   - 示例 1（发布指定分支）：`发布分支 feature/order-v1`\n"
				+ "   - 示例 2（按环境发布）：`发布分支 feature/order-v1 生产`\n"
				+ "   - 示例 3（按流水线分组发布）：`发布分支 分组=营销系统 分支=feature/coupon-v1`\n"
				+ "   - 示例 4（按分组与环境发布）：`发布分支 营销系统 feature/coupon-v1 prod`\n\n"
				+ "4. **🛑 取消分支发布（终止云效 Flow 流水线）**\n"
				+ "   - 指令格式：`取消发布 [分支名/或运行批次ID] [环境/默认test] [分组/仓库名]`\n"
				+ "   - 说明：向云效 Flow 发送终止指令，立即中止正在进行的构建发布\n"
				+ "   - 示例 1（按分支名取消）：`取消发布 feature/order-v1`\n"
				+ "   - 示例 2（取消生产环境发布）：`取消发布 feature/order-v1 生产`\n"
				+ "   - 示例 3（按分组取消发布）：`取消发布 分组=营销系统 分支=feature/coupon-v1`\n"
				+ "   - 示例 4（按批次ID取消）：`取消发布 987654321`\n\n"
				+ "5. **📂 流水线分组查询**\n"
				+ "   - 指令格式：`流水线分组` 或 `流水线分组 <关键词>`\n"
				+ "   - 说明：列出当前云效组织下配置的所有流水线业务分组\n\n"
				+ "6. **🔄 克隆/同步分支代码（本地 Git 缓存）**\n"
				+ "   - 指令格式：`克隆分支 [仓库名/默认changtai-cloud] [分支名/默认dev]`\n"
				+ "   - 说明：清空本地历史后重新浅克隆最新代码，缓存 2 小时\n"
				+ "   - 示例 1（默认项目与分支）：`克隆分支`\n"
				+ "   - 示例 2（自定义分支）：`克隆分支 dev` 或 `克隆分支 master`\n"
				+ "   - 示例 3（完整指定）：`克隆分支 changtai-cloud dev`\n\n"
				+ "6. **🔍 分支代码扫描与大模型审计**\n"
				+ "   - 快速扫描（最多 3 轮，可补读源码）：`代码扫描 [项目名/默认changtai-cloud] [分支名/默认dev] <问题诉求>`\n"
				+ "   - 深度探查（多轮 Agent）：`深度扫描 [项目名/默认changtai-cloud] [分支名/默认dev] <问题诉求>`\n"
				+ "   - 示例 1（快速梳理）：`代码扫描 梳理实报费用新增的校验逻辑`\n"
				+ "   - 示例 2（多轮深度探查表名）：`深度扫描 调拨单涉及的表名是哪些`\n"
				+ "   - 示例 3（指定分支深度扫描）：`深度扫描 分支 dev 调拨单涉及的表名`\n\n"
				+ "7. **💬 OpenCode 技术通用问答**\n"
				+ "   - 直接在群内输入任何技术问题、SQL 调优或代码重构问题，即可实时解答！";
	}
}
