package org.example.config;

import com.ct.common.core.constant.Constants;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode 大模型接入配置
 *
 * @author zeymo
 */
@Data
@Component
@ConfigurationProperties(prefix = "opencode")
public class OpenCodeProperties {

    /**
     * 是否启用 OpenCode 大模型
     */
    private Boolean enabled = true;

    /**
     * OpenCode / OpenAI 兼容的推理接口地址
     */
    private String apiUrl = "https://opencode.ai/inference/openai/v1/chat/completions";

    /**
     * API 密钥 / Token
     */
    private String apiKey;

    /**
     * 默认主模型名称
     */
    private String model = "kimi-k2.6";

    /**
     * 多模型配置列表
     */
    private List<OpenCodeModelItem> models = new ArrayList<>();

    /**
     * 单个模型最大重试次数 (默认 3 次)
     */
    private Integer maxRetriesPerModel = Constants.THREE;

    /**
     * 最大切换模型数量 (默认 3 个)
     */
    private Integer maxModelSwitchCount = Constants.THREE;

    /**
     * 系统提示词
     */
    private String systemPrompt = "你是一名高效、专业的 AI 编程与技术助手，请用简洁清晰的中文提供技术解答或代码方案。";

    /**
     * 采样温度
     */
    private Double temperature = 0.7;

    /**
     * 最大回复 Token 数
     */
    private Integer maxTokens = Constants.TWO_THOUSAND_FORTY_EIGHT;

    /**
     * 连接超时时间（毫秒，默认 10000 即 10 秒）
     */
    private Integer connectTimeout = Constants.TEN_THOUSAND;

    /**
     * 读取超时时间（毫秒，默认 180000 即 3 分钟，防止深度代码分析超时）
     */
    private Integer readTimeout = Constants.ONE_HUNDRED_EIGHTY_THOUSAND;

    /**
     * 获取按顺序调用的有效模型列表 (确保至少有配置的模型可切换)
     *
     * @return 待调用的模型配置列表
     */
    public List<OpenCodeModelItem> getEffectiveModelList() {
        List<OpenCodeModelItem> list = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(this.models)) {
            for (OpenCodeModelItem item : this.models) {
                if (item != null && StringUtils.isNotBlank(item.getModel())) {
                    list.add(item);
                }
            }
        }

        // 若未显式配置多模型列表，则使用主模型作为首选
        if (CollectionUtils.isEmpty(list)) {
            String primaryModel = StringUtils.isNotBlank(this.model) ? this.model.trim() : "kimi-k2.6";
            list.add(OpenCodeModelItem.builder().model(primaryModel).build());
        }

        // 如果配置的模型少于 3 个，自动补充通用备选模型以保证能满足 3 个模型的切换需求
        if (list.size() < Constants.THREE) {
            String[] defaultFallbacks = new String[]{"qwen-2.5-coder-32b", "deepseek-chat", "gpt-4o-mini"};
            for (String fallback : defaultFallbacks) {
                if (list.size() >= Constants.THREE) {
                    break;
                }
                boolean exists = false;
                for (OpenCodeModelItem existing : list) {
                    if (StringUtils.equalsIgnoreCase(existing.getModel(), fallback)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(OpenCodeModelItem.builder().model(fallback).build());
                }
            }
        }

        return list;
    }
}
