package org.example.service;

import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoBranchQueryParam;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;

import java.util.List;

/**
 * 云效 DevOps 代码库与分支管理服务
 *
 * @author zeymo
 */
public interface YunxiaoDevopsService {

    /**
     * 查询云效项目(代码库)列表
     *
     * @param queryParam 查询参数
     * @return 代码库列表
     */
    List<YunxiaoRepositoryDTO> listRepositories(YunxiaoRepositoryQueryParam queryParam);

    /**
     * 查询指定代码库的分支列表
     *
     * @param repositoryId 代码库ID或全路径
     * @param queryParam   分支查询参数
     * @return 分支列表
     */
    List<YunxiaoBranchDTO> listRepositoryBranches(String repositoryId, YunxiaoBranchQueryParam queryParam);

    /**
     * 查询云效项目及其对应的分支列表
     *
     * @param queryParam 查询参数
     * @return 包含分支列表的代码库列表
     */
    List<YunxiaoRepositoryDTO> listRepositoriesWithBranches(YunxiaoRepositoryQueryParam queryParam);

    /**
     * 在指定代码库中创建新分支
     *
     * @param createParam 创建分支参数
     * @return 创建的分支信息
     */
    YunxiaoBranchDTO createBranch(YunxiaoBranchCreateParam createParam);

    /**
     * 格式化项目与分支列表为群聊展示文本
     *
     * @param search 搜索关键字，可为空
     * @return 格式化后的 Markdown/文本消息
     */
    String formatRepositoriesAndBranchesForChat(String search);

    /**
     * 解析用户群聊输入并创建分支
     *
     * @param userQuestion 用户提问内容
     * @return 创建结果或引导文本
     */
    String createBranchForChat(String userQuestion);
}
