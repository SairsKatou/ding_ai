package org.example.service;

import org.example.domain.dto.CodeAgentAnalysisDTO;
import org.example.domain.dto.OpenChatMessageDTO;
import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;

import java.io.File;
import java.util.List;

/**
 * 云效代码 Agent 多轮 Tool Calling 深度探查服务接口
 *
 * @author zeymo
 */
public interface YunxiaoCodeAgentService {

    /**
     * 执行多轮自主 Tool Calling 深度探查与分析
     *
     * @param param          扫描参数
     * @param repoDir        本地克隆仓库目录
     * @param actualRepoName 仓库名
     * @param actualRepoId   仓库 ID
     * @return 深度扫描与分析结果
     */
    YunxiaoCodeScanResultDTO executeDeepScan(YunxiaoCodeScanParam param, File repoDir,
                                            String actualRepoName, String actualRepoId);

    /**
     * 在已组装好的上下文（如普通扫描预先检索的代码片段）基础上，允许大模型调用探查工具补充源码；
     * 最多 maxTurns 次大模型调用，最后一次不提供工具，强制输出结论
     *
     * @param initialMessages 初始上下文（system + user）
     * @param repoDir         本地克隆仓库目录
     * @param maxTurns        最多大模型调用次数
     * @return 分析结果
     * @throws IllegalStateException 大模型调用失败且没有任何工具结果可供汇总时抛出
     */
    CodeAgentAnalysisDTO analyzeWithTools(List<OpenChatMessageDTO> initialMessages, File repoDir, int maxTurns);
}
