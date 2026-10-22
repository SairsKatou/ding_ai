package org.example.service;

import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.OpenChatToolDTO;

import java.util.List;

/**
 * OpenCode 大模型服务接口
 *
 * @author zeymo
 */
public interface OpenCodeService {

    /**
     * 单轮提问对话
     *
     * @param userMessage 用户提问内容
     * @return 大模型回复文本
     */
    String chat(String userMessage);

    /**
     * 多轮提问对话
     *
     * @param messages 历史对话消息列表
     * @return 大模型回复文本
     */
    String chat(List<OpenChatMessageDTO> messages);

    /**
     * 带有可用工具的多轮提问对话 (支持大模型发起 Tool Calling)
     *
     * @param messages 历史对话消息列表
     * @param tools    大模型可用工具列表
     * @return 大模型回复消息对象 (包含文本内容 content 或工具调用列表 toolCalls)
     */
    OpenChatMessageDTO chatWithTools(List<OpenChatMessageDTO> messages, List<OpenChatToolDTO> tools);
}
