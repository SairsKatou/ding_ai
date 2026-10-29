package org.example.service;

import org.example.domain.dto.CodeSearchTermsDTO;

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
     * 按本地动作词典提取提问中的动作词（如 新增 -> add/save/insert、校验 -> check/validate）
     *
     * @param question 用户提问
     * @return 英文动作 Token 集合
     */
    Set<String> resolveActionTokens(String question);

    /**
     * 判断业务字典是否已将提问映射到英文 Token；未映射时需要借助大模型推断
     *
     * @param keyword  核心业务词
     * @param question 完整提问文本
     * @return 是否已有英文 Token
     */
    boolean hasEnglishToken(String keyword, String question);

    /**
     * 借助大模型推断提问相关的英文检索词，区分业务实体词与动作词
     *
     * @param question 用户提问
     * @return 推断结果，调用失败时返回空集合
     */
    CodeSearchTermsDTO inferSearchTermsWithAI(String question);

    /**
     * 借助大模型推断相关的英文类名/表名前缀（实体词与动作词合并返回）
     *
     * @param question 用户提问
     * @return 大模型推断的英文 Token 集合
     */
    Set<String> inferEnglishTokensWithAI(String question);
}
