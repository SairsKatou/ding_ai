package org.example.service.impl;

import com.alibaba.fastjson.JSON;
import com.ct.common.core.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.config.OpenCodeModelItem;
import org.example.config.OpenCodeProperties;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatRequestDTO;
import org.example.domain.dto.OpenChatResponseChoiceDTO;
import org.example.domain.dto.OpenChatResponseDTO;
import org.example.domain.dto.OpenChatToolDTO;
import org.example.service.OpenCodeService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode 大模型对话服务实现类 (支持多模型配置与故障自动转移)
 *
 * @author zeymo
 */
@Slf4j
@Service
public class OpenCodeServiceImpl implements OpenCodeService {

    /**
     * 系统角色标识
     */
    private static final String ROLE_SYSTEM = "system";

    /**
     * 用户角色标识
     */
    private static final String ROLE_USER = "user";

    /**
     * Authorization Header 前缀
     */
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_OPENCODE_SESSION = "x-opencode-session";
    private static final String DEFAULT_SESSION_ID = "opencode-robot-session";

    @Resource
    private RestTemplate openCodeRestTemplate;

    @Resource
    private OpenCodeProperties openCodeProperties;

    /**
     * 单轮提问对话
     *
     * @param userMessage 用户提问内容
     * @return 大模型回复文本
     */
    @Override
    public String chat(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return "请输入您想咨询的问题。";
        }

        List<OpenChatMessageDTO> messages = new ArrayList<>(Constants.TWENTY);

        // 如果配置了系统提示词，先加入 system 角色消息
        if (StringUtils.isNotBlank(openCodeProperties.getSystemPrompt())) {
            messages.add(OpenChatMessageDTO.builder()
                    .role(ROLE_SYSTEM)
                    .content(openCodeProperties.getSystemPrompt())
                    .build());
        }

        messages.add(OpenChatMessageDTO.builder()
                .role(ROLE_USER)
                .content(userMessage.trim())
                .build());

        return chat(messages);
    }

    /**
     * 多轮提问对话 (支持多模型配置，单模型失败3次切换，共切换3个模型，9次全失败抛出异常)
     *
     * @param messages 历史对话消息列表
     * @return 大模型回复文本
     */
    @Override
    public String chat(List<OpenChatMessageDTO> messages) {
        OpenChatMessageDTO reply = chatWithTools(messages, null);
        return reply != null && reply.getContent() != null ? reply.getContent().trim() : "";
    }

    /**
     * 带有可用工具的多轮提问对话 (支持大模型发起 Tool Calling)
     *
     * @param messages 历史对话消息列表
     * @param tools    大模型可用工具列表
     * @return 大模型回复消息对象
     */
    @Override
    public OpenChatMessageDTO chatWithTools(List<OpenChatMessageDTO> messages, List<OpenChatToolDTO> tools) {
        if (CollectionUtils.isEmpty(messages)) {
            throw new IllegalArgumentException("提问内容不能为空");
        }

        if (BooleanUtils.isFalse(openCodeProperties.getEnabled())) {
            throw new IllegalStateException("OpenCode 大模型服务当前已禁用，请在配置中设置 opencode.enabled=true 启用");
        }

        List<OpenCodeModelItem> candidateModels = openCodeProperties.getEffectiveModelList();
        int maxModels = openCodeProperties.getMaxModelSwitchCount() != null
                ? openCodeProperties.getMaxModelSwitchCount()
                : Constants.THREE;
        int maxRetriesPerModel = openCodeProperties.getMaxRetriesPerModel() != null
                ? openCodeProperties.getMaxRetriesPerModel()
                : Constants.THREE;

        int totalFailures = Constants.ZERO;
        Exception lastException = null;

        int modelsToTry = Math.min(candidateModels.size(), maxModels);

        // 遍历切换模型，最多尝试 maxModels 个模型
        for (int modelIndex = Constants.ZERO; modelIndex < modelsToTry; modelIndex++) {
            OpenCodeModelItem modelItem = candidateModels.get(modelIndex);
            String modelName = modelItem.getModel();
            String apiUrl = StringUtils.isNotBlank(modelItem.getApiUrl()) ? modelItem.getApiUrl() : openCodeProperties.getApiUrl();
            String apiKey = StringUtils.isNotBlank(modelItem.getApiKey()) ? modelItem.getApiKey() : openCodeProperties.getApiKey();
            Double temperature = modelItem.getTemperature() != null ? modelItem.getTemperature() : openCodeProperties.getTemperature();
            Integer maxTokens = modelItem.getMaxTokens() != null ? modelItem.getMaxTokens() : openCodeProperties.getMaxTokens();

            // 单个模型最多尝试 maxRetriesPerModel 次
            for (int attempt = Constants.ONE; attempt <= maxRetriesPerModel; attempt++) {
                try {
                    log.info("开始调用第 {} 个模型 [{}], 该模型第 {}/{} 次尝试, 累计失败: {} 次",
                            modelIndex + Constants.ONE, modelName, attempt, maxRetriesPerModel, totalFailures);

                    OpenChatMessageDTO reply = invokeSingleModelForMessage(apiUrl, apiKey, modelName, temperature, maxTokens, messages, tools);
                    boolean hasTools = CollectionUtils.isNotEmpty(reply.getToolCalls());
                    log.info("大模型 [{}] 第 {} 次调用成功，包含工具调用: {}, 内容长度: {}",
                            modelName, attempt, hasTools, reply.getContent() != null ? reply.getContent().length() : Constants.ZERO);
                    return reply;
                } catch (Exception e) {
                    totalFailures++;
                    lastException = e;
                    log.warn("大模型 [{}] 第 {} 次尝试失败: {}, 累计总失败次数: {}/{}",
                            modelName, attempt, e.getMessage(), totalFailures, Constants.NINE);
                }
            }

            // 当前模型已用尽 3 次重试，若还有备用模型则准备切换
            if (modelIndex + Constants.ONE < modelsToTry) {
                OpenCodeModelItem nextModel = candidateModels.get(modelIndex + Constants.ONE);
                log.warn("大模型 [{}] 连续失败 {} 次，触发故障转移，切换至第 {} 个模型 [{}]...",
                        modelName, maxRetriesPerModel, modelIndex + Constants.TWO, nextModel.getModel());
            }
        }

        // 3 个模型，9 次尝试均全部失败，抛出异常
        String errorMsg = String.format("大模型调用全部失败：已尝试 %d 个模型，累计连续失败 %d 次。最后一次失败原因: %s",
                modelsToTry, totalFailures, (lastException != null ? lastException.getMessage() : "未知异常"));
        log.error(errorMsg, lastException);
        throw new RuntimeException(errorMsg, lastException);
    }

    /**
     * 调用单次大模型接口并返回消息对象
     *
     * @param apiUrl      接口地址
     * @param apiKey      API Key
     * @param modelName   模型名称
     * @param temperature 采样温度
     * @param maxTokens   最大 Token 数
     * @param messages    消息列表
     * @param tools       工具列表（可选）
     * @return 回复消息对象
     * @throws Exception 调用异常
     */
    private OpenChatMessageDTO invokeSingleModelForMessage(String apiUrl, String apiKey, String modelName,
                                                           Double temperature, Integer maxTokens,
                                                           List<OpenChatMessageDTO> messages,
                                                           List<OpenChatToolDTO> tools) throws Exception {
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalArgumentException("模型 [" + modelName + "] 未配置有效的 apiKey，请在配置中设置 opencode.apiKey");
        }

        OpenChatRequestDTO requestDTO = OpenChatRequestDTO.builder()
                .model(modelName)
                .messages(messages)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .stream(Boolean.FALSE)
                .tools(CollectionUtils.isNotEmpty(tools) ? tools : null)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + apiKey.trim());
        // 设置 OpenCode 路由及缓存所需的会话标识请求头
        headers.set(HEADER_OPENCODE_SESSION, DEFAULT_SESSION_ID);

        HttpEntity<String> httpEntity = new HttpEntity<>(JSON.toJSONString(requestDTO), headers);

        ResponseEntity<String> response = openCodeRestTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                httpEntity,
                String.class
        );

        String body = response.getBody();
        if (StringUtils.isBlank(body)) {
            throw new RuntimeException("模型 [" + modelName + "] 返回空响应体");
        }

        OpenChatResponseDTO responseDTO = JSON.parseObject(body, OpenChatResponseDTO.class);
        if (responseDTO == null || CollectionUtils.isEmpty(responseDTO.getChoices())) {
            throw new RuntimeException("模型 [" + modelName + "] 返回 choices 列表为空: " + body);
        }

        OpenChatResponseChoiceDTO firstChoice = responseDTO.getChoices().get(Constants.ZERO);
        if (firstChoice == null || firstChoice.getMessage() == null) {
            throw new RuntimeException("模型 [" + modelName + "] 未生成有效回复消息");
        }

        OpenChatMessageDTO message = firstChoice.getMessage();
        if (StringUtils.isBlank(message.getContent()) && CollectionUtils.isEmpty(message.getToolCalls())) {
            throw new RuntimeException("模型 [" + modelName + "] 未生成有效回复文本且未调用任何工具: " + body);
        }

        return message;
    }
}
