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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private Client robotClient;

    @Resource
    private AccessTokenService accessTokenService;

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
}
