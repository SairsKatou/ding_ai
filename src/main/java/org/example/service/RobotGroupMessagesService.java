package org.example.service;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.ct.common.core.constant.Constants;
import com.ct.common.core.enums.RobotEmotionStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.Objects;

/**
 * 钉钉机器人群消息发送服务
 *
 * @author zeymo
 */
@Slf4j
@Service
public class RobotGroupMessagesService {

    // 钉钉群消息 Markdown 模板标识
    private static final String MSG_KEY_SAMPLE_MARKDOWN = "sampleMarkdown";

    // 协议
    private static final String PROTOCOL_HTTPS = "https";

    // 钉钉中心节点
    private static final String REGION_ID_CENTRAL = "central";

    // 默认首屏展示标题
    private static final String DEFAULT_MSG_TITLE = "智能开发助手消息";

    // 钉钉机器人添加表情回应 API 地址
    private static final String EMOTION_REPLY_URL = "https://api.dingtalk.com/v1.0/robot/emotion/reply";

    // 钉钉机器人撤回表情回应 API 地址
    private static final String EMOTION_RECALL_URL = "https://api.dingtalk.com/v1.0/robot/emotion/recall";

    // 钉钉 OpenAPI AccessToken 请求头名称
    private static final String HEADER_ACCESS_TOKEN = "x-acs-dingtalk-access-token";

    private Client robotClient;

    @Resource
    private AccessTokenService accessTokenService;

    @Resource(name = "yunxiaoRestTemplate")
    private RestTemplate yunxiaoRestTemplate;

    @Value("${robot.code}")
    private String robotCode;

    /**
     * 初始化钉钉机器人客户端
     *
     * @throws Exception 初始化异常
     */
    @PostConstruct
    public void init() throws Exception {
        Config config = new Config();
        config.protocol = PROTOCOL_HTTPS;
        config.regionId = REGION_ID_CENTRAL;
        robotClient = new Client(config);
    }

    /**
     * 以 Markdown 格式发送群消息并在消息中 @用户
     *
     * @param openConversationId 群会话 ID
     * @param atUserId           需要 @ 的用户 staffId
     * @param text               Markdown 格式消息内容
     * @return 异步处理 key
     * @throws Exception 发送异常
     */
    public String send(String openConversationId, String atUserId, String text) throws Exception {
        OrgGroupSendHeaders orgGroupSendHeaders = new OrgGroupSendHeaders();
        orgGroupSendHeaders.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

        OrgGroupSendRequest orgGroupSendRequest = new OrgGroupSendRequest();
        // 设置 Markdown 消息模板：sampleMarkdown
        orgGroupSendRequest.setMsgKey(MSG_KEY_SAMPLE_MARKDOWN);
        orgGroupSendRequest.setRobotCode(robotCode);
        orgGroupSendRequest.setOpenConversationId(openConversationId);

        JSONObject msgParam = new JSONObject();
        // 首屏展示标题
        msgParam.put("title", resolveTitle(text));
        // Markdown 正文内容
        msgParam.put("text", text);
        // 需要 @ 的用户列表
        if (StringUtils.isNotBlank(atUserId)) {
            msgParam.put("atUserIds", Collections.singletonList(atUserId));
        }

        orgGroupSendRequest.setMsgParam(msgParam.toJSONString());

        try {
            OrgGroupSendResponse orgGroupSendResponse = robotClient.orgGroupSendWithOptions(
                    orgGroupSendRequest,
                    orgGroupSendHeaders,
                    new RuntimeOptions()
            );
            if (Objects.isNull(orgGroupSendResponse) || Objects.isNull(orgGroupSendResponse.getBody())) {
                log.error("RobotGroupMessagesService_send orgGroupSendWithOptions return error, response={}",
                        orgGroupSendResponse);
                return null;
            }
            return orgGroupSendResponse.getBody().getProcessQueryKey();
        } catch (TeaException e) {
            log.error("RobotGroupMessagesService_send orgGroupSendWithOptions throw TeaException, errCode={}, errorMessage={}",
                    e.getCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("RobotGroupMessagesService_send orgGroupSendWithOptions throw Exception", e);
            throw e;
        }
    }

    /**
     * 生成适合钉钉 Markdown 首屏消息列表展示的标题
     *
     * @param markdownText Markdown 文本内容
     * @return 标题文本
     */
    private String resolveTitle(String markdownText) {
        if (StringUtils.isBlank(markdownText)) {
            return DEFAULT_MSG_TITLE;
        }
        // 提取首行非空文本作为摘要标题
        String[] lines = markdownText.split("\r?\n");
        for (String line : lines) {
            String cleaned = line.replaceAll("^[#\\*\\-\\s>]+", "").trim();
            if (StringUtils.isNotBlank(cleaned)) {
                return cleaned.length() > Constants.THIRTY
                        ? cleaned.substring(Constants.ZERO, Constants.THIRTY)
                        : cleaned;
            }
        }
        return DEFAULT_MSG_TITLE;
    }

    /**
     * 为指定消息添加文字表情表态（如“思考中”、“完成”）
     *
     * @param openConversationId 群会话 ID
     * @param openMsgId          目标消息 ID
     * @param statusEnum         表情表态状态枚举
     */
    public void addEmotionReply(String openConversationId, String openMsgId, RobotEmotionStatusEnum statusEnum) {
        sendEmotionRequest(EMOTION_REPLY_URL, openConversationId, openMsgId, statusEnum);
    }

    /**
     * 撤回指定消息上的文字表情表态（如撤回“思考中”切换为“完成”）
     *
     * @param openConversationId 群会话 ID
     * @param openMsgId          目标消息 ID
     * @param statusEnum         表情表态状态枚举
     */
    public void recallEmotionReply(String openConversationId, String openMsgId, RobotEmotionStatusEnum statusEnum) {
        sendEmotionRequest(EMOTION_RECALL_URL, openConversationId, openMsgId, statusEnum);
    }

    /**
     * 执行钉钉机器人表情表态 HTTP 请求（非阻塞主流程，异常仅告警）
     *
     * @param url                请求接口地址
     * @param openConversationId 群会话 ID
     * @param openMsgId          目标消息 ID
     * @param statusEnum         表情表态状态枚举
     */
    private void sendEmotionRequest(String url, String openConversationId, String openMsgId,
                                    RobotEmotionStatusEnum statusEnum) {
        if (StringUtils.isBlank(openConversationId) || StringUtils.isBlank(openMsgId) || Objects.isNull(statusEnum)) {
            return;
        }
        try {
            // 获取企业内部应用 AccessToken
            String accessToken = accessTokenService.getAccessToken();
            if (StringUtils.isBlank(accessToken)) {
                log.warn("发送消息表情表态跳过：获取钉钉 AccessToken 为空, msgId={}", openMsgId);
                return;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HEADER_ACCESS_TOKEN, accessToken);

            // 构造文字表情请求体
            JSONObject textEmotion = new JSONObject();
            textEmotion.put("emotionId", statusEnum.getEmotionId());
            textEmotion.put("emotionName", statusEnum.getEmotionText());
            textEmotion.put("text", statusEnum.getEmotionText());
            textEmotion.put("backgroundId", statusEnum.getBackgroundId());

            JSONObject body = new JSONObject();
            body.put("robotCode", robotCode);
            body.put("openMsgId", openMsgId);
            body.put("openConversationId", openConversationId);
            // emotionType 为 2 表示文字表情表态
            body.put("emotionType", Constants.TWO);
            body.put("emotionName", statusEnum.getEmotionText());
            body.put("textEmotion", textEmotion);

            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            RestTemplate client = Objects.nonNull(yunxiaoRestTemplate) ? yunxiaoRestTemplate : new RestTemplate();
            client.exchange(url, HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            // 表态失败不影响主业务消息回复流程
            log.warn("执行钉钉消息表情表态失败, url={}, msgId={}, status={}, error={}",
                    url, openMsgId, statusEnum.getCode(), e.getMessage());
        }
    }
}
