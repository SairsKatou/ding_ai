package org.example.config;

import com.ct.common.core.constant.Constants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 云效及 AI HTTP 客户端配置
 *
 * @author zeymo
 */
@Configuration
public class YunxiaoDevopsConfig {

    /**
     * 配置用于调用云效 REST API 的 RestTemplate
     *
     * @return RestTemplate 实例
     */
    @Bean
    public RestTemplate yunxiaoRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Constants.FIVE_THOUSAND);
        factory.setReadTimeout(Constants.FIVE_THOUSAND);
        return new RestTemplate(factory);
    }

    /**
     * 配置用于调用 OpenCode 大模型的 RestTemplate (支持动态长读超时，防止大模型深度分析时超时)
     *
     * @param openCodeProperties OpenCode 配置属性
     * @return RestTemplate 实例
     */
    @Bean
    public RestTemplate openCodeRestTemplate(OpenCodeProperties openCodeProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int connectTimeout = openCodeProperties != null && openCodeProperties.getConnectTimeout() != null
                ? openCodeProperties.getConnectTimeout()
                : Constants.TEN_THOUSAND;
        int readTimeout = openCodeProperties != null && openCodeProperties.getReadTimeout() != null
                ? openCodeProperties.getReadTimeout()
                : Constants.ONE_HUNDRED_EIGHTY_THOUSAND;

        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
