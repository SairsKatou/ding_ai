package org.example.service;

import org.example.domain.dto.YunxiaoCodeSnippetDTO;

import java.io.File;
import java.util.List;

/**
 * 云效本地 Git 浅克隆与缓存管理服务
 *
 * @author zeymo
 */
public interface YunxiaoGitCloneService {

    /**
     * 获取或准备本地克隆仓库目录（2 小时内直接复用缓存，过期或不存在时重新浅克隆）
     *
     * @param repoId   代码库 ID
     * @param repoName 代码库名称
     * @param branch   分支名称
     * @param cloneUrl 代码库克隆地址
     * @return 本地仓库根目录
     */
    File prepareLocalRepository(String repoId, String repoName, String branch, String cloneUrl);

    /**
     * 在本地仓库中进行全量源码检索并提取高质量业务代码片段
     *
     * @param repoDir       本地仓库根目录
     * @param searchKeyword 搜索业务关键词
     * @param question      用户原始提问（用于补充检索词、动作词与文件类型过滤）
     * @param maxFiles      最大提取相关文件数
     * @return 命中的代码片段列表
     */
    List<YunxiaoCodeSnippetDTO> scanLocalRepository(File repoDir, String searchKeyword, String question, int maxFiles);

    /**
     * 强制重新克隆指定分支（先删除历史本地克隆，后重新浅克隆并锁定 2 小时缓存）
     *
     * @param repoId   代码库 ID
     * @param repoName 代码库名称
     * @param branch   分支名称（不指定默认 dev）
     * @param cloneUrl 代码库克隆地址
     * @return 本地仓库根目录
     */
    File forceRecloneRepository(String repoId, String repoName, String branch, String cloneUrl);

    /**
     * 解析钉钉群聊克隆分支指令，先删除历史克隆缓存，再拉取新增（若不指定分支则默认 dev）
     *
     * @param userQuestion 用户提问内容
     * @return 执行结果信息
     */
    String cloneBranchForChat(String userQuestion);
}
