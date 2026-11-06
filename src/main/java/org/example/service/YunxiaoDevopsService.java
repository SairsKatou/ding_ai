package org.example.service;

import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoBranchQueryParam;
import org.example.domain.dto.YunxiaoPipelineCancelDTO;
import org.example.domain.dto.YunxiaoPipelineCancelParam;
import org.example.domain.dto.YunxiaoPipelineDTO;
import org.example.domain.dto.YunxiaoPipelineGroupDTO;
import org.example.domain.dto.YunxiaoPipelineGroupQueryParam;
import org.example.domain.dto.YunxiaoPipelineQueryParam;
import org.example.domain.dto.YunxiaoPipelineRunDTO;
import org.example.domain.dto.YunxiaoPipelineRunParam;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;

import java.util.List;

/**
 * 云效 DevOps 代码库、分支与流水线管理服务
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

    /**
     * 查询云效流水线列表
     *
     * @param queryParam 查询参数
     * @return 流水线列表
     */
    List<YunxiaoPipelineDTO> listPipelines(YunxiaoPipelineQueryParam queryParam);

    /**
     * 触发指定分支的云效流水线发布运行
     *
     * @param runParam 流水线运行参数
     * @return 流水线运行结果
     */
    YunxiaoPipelineRunDTO runPipeline(YunxiaoPipelineRunParam runParam);

    /**
     * 解析钉钉群聊分支发布指令并触发流水线发布
     *
     * @param userQuestion 用户在群聊中的输入
     * @return 发布结果展示 Markdown 文本
     */
    String releaseBranchForChat(String userQuestion);

    /**
     * 终止/取消指定流水线运行实例
     *
     * @param cancelParam 取消参数
     * @return 取消结果
     */
    YunxiaoPipelineCancelDTO stopPipelineRun(YunxiaoPipelineCancelParam cancelParam);

    /**
     * 解析钉钉群聊取消分支发布指令并执行取消
     *
     * @param userQuestion 用户在群聊中的输入
     * @return 取消结果展示 Markdown 文本
     */
    String cancelReleaseBranchForChat(String userQuestion);

    /**
     * 查询云效流水线分组列表
     *
     * @param queryParam 查询参数
     * @return 流水线分组列表
     */
    List<YunxiaoPipelineGroupDTO> listPipelineGroups(YunxiaoPipelineGroupQueryParam queryParam);

    /**
     * 格式化流水线分组列表为群聊展示文本
     *
     * @param userQuestion 用户在群聊中的输入
     * @return 格式化后的 Markdown 文本
     */
    String listPipelineGroupsForChat(String userQuestion);

    /**
     * 查询云效流水线分组及其下属各具体流水线列表
     *
     * @param queryParam 查询参数
     * @return 包含各分组下流水线列表的聚合信息
     */
    List<YunxiaoPipelineGroupDTO> listPipelineGroupsWithPipelines(YunxiaoPipelineGroupQueryParam queryParam);
}
