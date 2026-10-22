package org.example.service;

import java.util.Set;

/**
 * 代码扫描关键词提炼与搜索 Token 构建服务
 *
 * @author zeymo
 */
public interface CodeSearchKeywordService {

    /**
     * 从用户自然语言提问中提炼核心业务关键词
     *
     * @param explicitKeyword 显式指定关键词（可选）
     * @param question        用户提问
     * @return 提炼后的核心关键词
     */
    String resolveCoreKeyword(String explicitKeyword, String question);

    /**
     * 构建多粒度的中文与对应英文搜索 Token 词表
     *
     * @param keyword  核心业务词
     * @param question 完整提问文本
     * @return 关联的搜索 Token 集合
     */
    Set<String> buildSearchTokens(String keyword, String question);

    /**
     * 当初次检索未命中任何文件时，借助大模型极速推断相关的英文类名/表名前缀
     *
     * @param question 用户提问
     * @return 大模型推断的英文 Token 集合
     */
    Set<String> inferEnglishTokensWithAI(String question);
}
