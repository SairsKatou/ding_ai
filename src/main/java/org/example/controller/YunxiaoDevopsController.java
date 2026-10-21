package org.example.controller;

import com.ct.common.core.web.domain.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoBranchQueryParam;
import org.example.domain.dto.YunxiaoRepositoryDTO;
import org.example.domain.dto.YunxiaoRepositoryQueryParam;
import org.example.service.YunxiaoDevopsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 云效代码库与分支接口控制器
 *
 * @author zeymo
 */
@Api(tags = "云效DevOps代码库与分支管理")
@RestController
@RequestMapping("/api/yunxiao")
public class YunxiaoDevopsController {

    @Resource
    private YunxiaoDevopsService yunxiaoDevopsService;

    @ApiOperation(value = "获取云效项目(代码库)列表")
    @GetMapping("/repositories")
    public Result<List<YunxiaoRepositoryDTO>> listRepositories(YunxiaoRepositoryQueryParam queryParam) {
        // 调用服务层获取代码库列表
        List<YunxiaoRepositoryDTO> list = yunxiaoDevopsService.listRepositories(queryParam);
        return Result.success(list);
    }

    @ApiOperation(value = "获取指定云效代码库的分支列表")
    @GetMapping("/repositories/{repositoryId}/branches")
    public Result<List<YunxiaoBranchDTO>> listRepositoryBranches(
            @ApiParam(value = "代码库ID", required = true) @PathVariable("repositoryId") String repositoryId,
            YunxiaoBranchQueryParam queryParam) {
        // 查询指定仓库的分支数据
        List<YunxiaoBranchDTO> branchList = yunxiaoDevopsService.listRepositoryBranches(repositoryId, queryParam);
        return Result.success(branchList);
    }

    @ApiOperation(value = "获取云效项目及其对应分支列表")
    @GetMapping("/repositories-with-branches")
    public Result<List<YunxiaoRepositoryDTO>> listRepositoriesWithBranches(YunxiaoRepositoryQueryParam queryParam) {
        // 查询代码库并组合下属分支列表
        List<YunxiaoRepositoryDTO> list = yunxiaoDevopsService.listRepositoriesWithBranches(queryParam);
        return Result.success(list);
    }

    @ApiOperation(value = "在指定代码库中创建新分支")
    @PostMapping("/branches")
    public Result<YunxiaoBranchDTO> createBranch(@RequestBody YunxiaoBranchCreateParam createParam) {
        // 调用服务层在代码库中创建分支
        YunxiaoBranchDTO branch = yunxiaoDevopsService.createBranch(createParam);
        return Result.success(branch);
    }

    @ApiOperation(value = "指定代码库路径创建新分支")
    @PostMapping("/repositories/{repositoryId}/branches")
    public Result<YunxiaoBranchDTO> createBranchByRepoId(
            @ApiParam(value = "代码库ID", required = true) @PathVariable("repositoryId") String repositoryId,
            @RequestBody YunxiaoBranchCreateParam createParam) {
        // 设置路径中的代码库ID
        if (createParam == null) {
            createParam = new YunxiaoBranchCreateParam();
        }
        createParam.setRepositoryId(repositoryId);
        YunxiaoBranchDTO branch = yunxiaoDevopsService.createBranch(createParam);
        return Result.success(branch);
    }
}
