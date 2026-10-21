package org.example.service;

import org.example.domain.dto.YunxiaoCodeScanParam;
import org.example.domain.dto.YunxiaoCodeScanResultDTO;

/**
 * 云效分支代码扫描与大模型分析服务
 *
 * @author zeymo
 */
public interface YunxiaoCodeScanService {

    /**
     * 扫描指定云效代码库分支的代码，并结合用户提问交由大模型进行深度梳理分析
     *
     * @param param 扫描分析请求参数
     * @return 扫描与分析结果
     */
    YunxiaoCodeScanResultDTO scanAndAnalyzeBranch(YunxiaoCodeScanParam param);

    /**
     * 解析钉钉群聊消息中的分支扫描指令并执行大模型分析
     *
     * @param chatInput 用户群聊输入文本
     * @return 格式化后的分析报告文本
     */
    String scanAndAnalyzeForChat(String chatInput);
}
