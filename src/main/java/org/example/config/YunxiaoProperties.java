package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云云效配置属性
 *
 * @author zeymo
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.devops")
public class YunxiaoProperties {

    /**
     * 云效 OpenAPI 服务地址 (个人访问令牌模式使用 openapi-rdc.aliyuncs.com)
     */
    private String endpoint = "openapi-rdc.aliyuncs.com";

    /**
     * 阿里云 AccessKey ID
     */
    private String accessKeyId;

    /**
     * 阿里云 AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * 云效企业组织 ID
     */
    private String organizationId;

    /**
     * 个人访问令牌（可选，若未配置 AccessKey 则使用此项）
     */
    private String accessToken;

    /**
     * 代码本地浅克隆缓存根目录 (默认 data/repo_cache)
     */
    private String cloneCacheDir = "data/repo_cache";

    /**
     * 本地克隆缓存过期时间（小时，默认 2 小时）
     */
    private Integer cloneCacheExpireHours = 2;
}
