package org.example.controller;

import com.ct.common.core.web.domain.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
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
 * 云效代码库、分支与流水线发布接口控制器
 *
 * @author zeymo
 */
@Api(tags = "云效DevOps代码库、分支与流水线管理")
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

    @ApiOperation(value = "查询云效流水线列表")
    @GetMapping("/pipelines")
    public Result<List<YunxiaoPipelineDTO>> listPipelines(YunxiaoPipelineQueryParam queryParam) {
        // 调用服务层获取流水线列表
        List<YunxiaoPipelineDTO> list = yunxiaoDevopsService.listPipelines(queryParam);
        return Result.success(list);
    }

    @ApiOperation(value = "指定流水线ID触发运行与发布")
    @PostMapping("/pipelines/{pipelineId}/run")
    public Result<YunxiaoPipelineRunDTO> runPipelineById(
            @ApiParam(value = "流水线ID", required = true) @PathVariable("pipelineId") Long pipelineId,
            @RequestBody YunxiaoPipelineRunParam runParam) {
        // 组装运行参数
        if (runParam == null) {
            runParam = new YunxiaoPipelineRunParam();
        }
        runParam.setPipelineId(pipelineId);
        // 执行流水线发布运行
        YunxiaoPipelineRunDTO runResult = yunxiaoDevopsService.runPipeline(runParam);
        return Result.success(runResult);
    }

    @ApiOperation(value = "触发指定分支的自动化发布")
    @PostMapping("/branches/release")
    public Result<YunxiaoPipelineRunDTO> releaseBranch(@RequestBody YunxiaoPipelineRunParam runParam) {
        // 调用服务层触发分支发布
        YunxiaoPipelineRunDTO runResult = yunxiaoDevopsService.runPipeline(runParam);
        return Result.success(runResult);
    }

    @ApiOperation(value = "指定流水线ID与运行批次ID终止流水线运行")
    @PostMapping("/pipelines/{pipelineId}/runs/{pipelineRunId}/stop")
    public Result<YunxiaoPipelineCancelDTO> stopPipelineRunById(
            @ApiParam(value = "流水线ID", required = true) @PathVariable("pipelineId") Long pipelineId,
            @ApiParam(value = "运行批次ID", required = true) @PathVariable("pipelineRunId") Long pipelineRunId) {
        // 组装取消请求参数
        YunxiaoPipelineCancelParam cancelParam = YunxiaoPipelineCancelParam.builder()
                .pipelineId(pipelineId)
                .pipelineRunId(pipelineRunId)
                .build();
        // 执行取消流水线运行
        YunxiaoPipelineCancelDTO cancelResult = yunxiaoDevopsService.stopPipelineRun(cancelParam);
        return Result.success(cancelResult);
    }

    @ApiOperation(value = "取消指定分支或批次的发布任务")
    @PostMapping("/branches/cancel-release")
    public Result<YunxiaoPipelineCancelDTO> cancelReleaseBranch(@RequestBody YunxiaoPipelineCancelParam cancelParam) {
        // 调用服务层取消发布
        YunxiaoPipelineCancelDTO cancelResult = yunxiaoDevopsService.stopPipelineRun(cancelParam);
        return Result.success(cancelResult);
    }

    @ApiOperation(value = "查询云效流水线分组列表")
    @GetMapping("/pipeline-groups")
    public Result<List<YunxiaoPipelineGroupDTO>> listPipelineGroups(YunxiaoPipelineGroupQueryParam queryParam) {
        // 调用服务层获取流水线分组列表
        List<YunxiaoPipelineGroupDTO> groupList = yunxiaoDevopsService.listPipelineGroups(queryParam);
        return Result.success(groupList);
    }

    @ApiOperation(value = "查询流水线分组及其下属各具体流水线列表")
    @GetMapping("/pipeline-groups/pipelines")
    public Result<List<YunxiaoPipelineGroupDTO>> listPipelineGroupsWithPipelines(YunxiaoPipelineGroupQueryParam queryParam) {
        // 调用服务层获取各分组下聚合的流水线列表
        List<YunxiaoPipelineGroupDTO> list = yunxiaoDevopsService.listPipelineGroupsWithPipelines(queryParam);
        return Result.success(list);
    }
}
