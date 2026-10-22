package org.example.service;

import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;

import java.io.File;

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
}
