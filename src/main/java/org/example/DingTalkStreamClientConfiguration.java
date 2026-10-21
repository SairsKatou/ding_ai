package org.example;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import org.example.callback.chatbot.ChatBotCallbackListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

/**
 * 钉钉 Stream Client 客户端配置
 *
 * @author zeymo
 */
@Configuration
public class DingTalkStreamClientConfiguration {

    @Value("${app.appKey}")
    private String clientId;

    @Value("${app.appSecret}")
    private String clientSecret;

    @Resource
    private ChatBotCallbackListener chatBotCallbackListener;

    /**
     * 配置 OpenDingTalkClient 客户端并配置初始化启动方法
     *
     * @return 钉钉客户端实例
     * @throws Exception 配置异常
     */
    @Bean(initMethod = "start")
    public OpenDingTalkClient configureStreamClient() throws Exception {
        // 构建 Stream 客户端
        return OpenDingTalkStreamClientBuilder.custom()
                // 配置应用的身份凭证 (appKey & appSecret)
                .credential(new AuthClientCredential(clientId, clientSecret))
                // 注册机器人群消息回调监听器
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, chatBotCallbackListener)
                .build();
    }
}
