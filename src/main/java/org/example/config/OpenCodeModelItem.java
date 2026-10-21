package org.example.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OpenCode 单个大模型配置项
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenCodeModelItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模型标识名称 (例如 kimi-k2.6, qwen-2.5-coder-32b, deepseek-chat)
     */
    private String model;

    /**
     * 模型专属 API 端点 (可选，为空时使用全局 apiUrl)
     */
    private String apiUrl;

    /**
     * 模型专属 API 密钥 (可选，为空时使用全局 apiKey)
     */
    private String apiKey;

    /**
     * 采样温度 (可选，为空时使用全局 temperature)
     */
    private Double temperature;

    /**
     * 最大回复 Token 数量 (可选，为空时使用全局 maxTokens)
     */
    private Integer maxTokens;
}
