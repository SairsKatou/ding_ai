package org.example.service;

import org.example.domain.dto.OpenChatMessageDTO;

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
}
