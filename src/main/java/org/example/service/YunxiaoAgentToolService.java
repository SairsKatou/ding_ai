package org.example.service;

import org.example.domain.dto.OpenChatToolDTO;

import java.io.File;
import java.util.List;

/**
 * 云效代码 Agent 本地执行工具服务接口
 *
 * @author zeymo
 */
public interface YunxiaoAgentToolService {

    /**
     * 获取向大模型声明的可用工具清单与 Schema
     *
     * @return 工具声明列表
     */
    List<OpenChatToolDTO> getToolDefinitions();

    /**
     * 执行指定的本地代码探查工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 入参 JSON 字符串
     * @param repoDir       本地克隆仓库目录
     * @return 工具执行输出结果文本
     */
    String executeTool(String toolName, String argumentsJson, File repoDir);
}
