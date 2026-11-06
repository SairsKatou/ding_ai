package org.example.controller;

import com.ct.common.core.constant.Constants;
import com.ct.common.core.enums.PipelineRunStatusEnum;
import com.ct.common.core.web.domain.Result;
import org.example.domain.dto.YunxiaoBranchCreateParam;
import org.example.domain.dto.YunxiaoBranchDTO;
import org.example.domain.dto.YunxiaoPipelineCancelDTO;
import org.example.domain.dto.YunxiaoPipelineCancelParam;
import org.example.domain.dto.YunxiaoPipelineDTO;
import org.example.domain.dto.YunxiaoPipelineGroupDTO;
import org.example.domain.dto.YunxiaoPipelineGroupQueryParam;
import org.example.domain.dto.YunxiaoPipelineQueryParam;
import org.example.domain.dto.YunxiaoPipelineRunDTO;
import org.example.domain.dto.YunxiaoPipelineRunParam;
import org.example.service.YunxiaoDevopsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

/**
 * 云效DevOps控制器单元测试
 *
 * @author zeymo
 */
@ExtendWith(MockitoExtension.class)
public class YunxiaoDevopsControllerTest {

    @Mock
    private YunxiaoDevopsService yunxiaoDevopsService;

    @InjectMocks
    private YunxiaoDevopsController yunxiaoDevopsController;

    /**
     * 测试查询流水线列表接口
     */
    @Test
    public void testListPipelines() {
        YunxiaoPipelineDTO pipelineDTO = YunxiaoPipelineDTO.builder()
                .pipelineId(123456L)
                .pipelineName("test-pipeline")
                .build();
        Mockito.when(yunxiaoDevopsService.listPipelines(Mockito.any(YunxiaoPipelineQueryParam.class)))
                .thenReturn(Collections.singletonList(pipelineDTO));

        YunxiaoPipelineQueryParam queryParam = new YunxiaoPipelineQueryParam();
        queryParam.setPipelineName("test");
        // 调用控制器查询流水线列表
        Result<List<YunxiaoPipelineDTO>> result = yunxiaoDevopsController.listPipelines(queryParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals(Constants.ONE, result.getData().size());
        Assertions.assertEquals(123456L, result.getData().get(Constants.ZERO).getPipelineId());
    }

    /**
     * 测试指定流水线ID运行接口
     */
    @Test
    public void testRunPipelineById() {
        YunxiaoPipelineRunDTO runDTO = YunxiaoPipelineRunDTO.builder()
                .pipelineId(123456L)
                .pipelineRunId(789012L)
                .branchName("feature/v1")
                .status(PipelineRunStatusEnum.RUNNING.getCode())
                .build();
        Mockito.when(yunxiaoDevopsService.runPipeline(Mockito.any(YunxiaoPipelineRunParam.class)))
                .thenReturn(runDTO);

        YunxiaoPipelineRunParam runParam = YunxiaoPipelineRunParam.builder()
                .branchName("feature/v1")
                .build();

        // 调用指定流水线ID运行接口
        Result<YunxiaoPipelineRunDTO> result = yunxiaoDevopsController.runPipelineById(123456L, runParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals(789012L, result.getData().getPipelineRunId());
    }

    /**
     * 测试分支便捷发布接口
     */
    @Test
    public void testReleaseBranch() {
        YunxiaoPipelineRunDTO runDTO = YunxiaoPipelineRunDTO.builder()
                .pipelineId(123456L)
                .pipelineRunId(998877L)
                .branchName("feature/order-v1")
                .status(PipelineRunStatusEnum.RUNNING.getCode())
                .build();
        Mockito.when(yunxiaoDevopsService.runPipeline(Mockito.any(YunxiaoPipelineRunParam.class)))
                .thenReturn(runDTO);

        YunxiaoPipelineRunParam runParam = YunxiaoPipelineRunParam.builder()
                .repositoryId("changtai-cloud")
                .branchName("feature/order-v1")
                .build();

        // 调用分支发布接口
        Result<YunxiaoPipelineRunDTO> result = yunxiaoDevopsController.releaseBranch(runParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals("feature/order-v1", result.getData().getBranchName());
    }

    /**
     * 测试创建分支接口
     */
    @Test
    public void testCreateBranch() {
        YunxiaoBranchDTO branchDTO = YunxiaoBranchDTO.builder()
                .name("feature/new-branch")
                .build();
        Mockito.when(yunxiaoDevopsService.createBranch(Mockito.any(YunxiaoBranchCreateParam.class)))
                .thenReturn(branchDTO);

        YunxiaoBranchCreateParam createParam = YunxiaoBranchCreateParam.builder()
                .repositoryId("1001")
                .branchName("feature/new-branch")
                .ref("master")
                .build();

        // 调用创建分支接口
        Result<YunxiaoBranchDTO> result = yunxiaoDevopsController.createBranch(createParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals("feature/new-branch", result.getData().getName());
    }

    /**
     * 测试指定流水线ID与运行批次ID终止运行接口
     */
    @Test
    public void testStopPipelineRunById() {
        YunxiaoPipelineCancelDTO cancelDTO = YunxiaoPipelineCancelDTO.builder()
                .pipelineId(123456L)
                .pipelineRunId(998877L)
                .success(Boolean.TRUE)
                .message("成功终止")
                .build();
        Mockito.when(yunxiaoDevopsService.stopPipelineRun(Mockito.any(YunxiaoPipelineCancelParam.class)))
                .thenReturn(cancelDTO);

        // 调用按ID终止流水线运行接口
        Result<YunxiaoPipelineCancelDTO> result = yunxiaoDevopsController.stopPipelineRunById(123456L, 998877L);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertTrue(result.getData().getSuccess());
    }

    /**
     * 测试取消分支发布便捷接口
     */
    @Test
    public void testCancelReleaseBranch() {
        YunxiaoPipelineCancelDTO cancelDTO = YunxiaoPipelineCancelDTO.builder()
                .pipelineId(123456L)
                .branchName("feature/order-v1")
                .success(Boolean.TRUE)
                .message("成功取消发布")
                .build();
        Mockito.when(yunxiaoDevopsService.stopPipelineRun(Mockito.any(YunxiaoPipelineCancelParam.class)))
                .thenReturn(cancelDTO);

        YunxiaoPipelineCancelParam cancelParam = YunxiaoPipelineCancelParam.builder()
                .branchName("feature/order-v1")
                .build();

        // 调用取消发布接口
        Result<YunxiaoPipelineCancelDTO> result = yunxiaoDevopsController.cancelReleaseBranch(cancelParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals("feature/order-v1", result.getData().getBranchName());
        Assertions.assertTrue(result.getData().getSuccess());
    }

    /**
     * 测试查询流水线分组列表接口
     */
    @Test
    public void testListPipelineGroups() {
        YunxiaoPipelineGroupDTO groupDTO = YunxiaoPipelineGroupDTO.builder()
                .groupId(9988L)
                .groupName("测试微服务分组")
                .build();
        Mockito.when(yunxiaoDevopsService.listPipelineGroups(Mockito.any(YunxiaoPipelineGroupQueryParam.class)))
                .thenReturn(Collections.singletonList(groupDTO));

        YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
        queryParam.setGroupName("微服务");

        Result<List<YunxiaoPipelineGroupDTO>> result = yunxiaoDevopsController.listPipelineGroups(queryParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals(Constants.ONE, result.getData().size());
        Assertions.assertEquals(9988L, result.getData().get(Constants.ZERO).getGroupId());
        Assertions.assertEquals("测试微服务分组", result.getData().get(Constants.ZERO).getGroupName());
    }

    /**
     * 测试查询流水线分组及其下属各具体流水线列表接口
     */
    @Test
    public void testListPipelineGroupsWithPipelines() {
        YunxiaoPipelineDTO pipelineDTO = YunxiaoPipelineDTO.builder()
                .pipelineId(667788L)
                .pipelineName("ct-modules-scm-测试环境")
                .groupId(9988L)
                .build();
        YunxiaoPipelineGroupDTO groupDTO = YunxiaoPipelineGroupDTO.builder()
                .groupId(9988L)
                .groupName("测试环境")
                .pipelines(Collections.singletonList(pipelineDTO))
                .build();

        Mockito.when(yunxiaoDevopsService.listPipelineGroupsWithPipelines(Mockito.any(YunxiaoPipelineGroupQueryParam.class)))
                .thenReturn(Collections.singletonList(groupDTO));

        YunxiaoPipelineGroupQueryParam queryParam = new YunxiaoPipelineGroupQueryParam();
        Result<List<YunxiaoPipelineGroupDTO>> result = yunxiaoDevopsController.listPipelineGroupsWithPipelines(queryParam);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(Constants.SUCCESS, result.getCode());
        Assertions.assertEquals(Constants.ONE, result.getData().size());
        Assertions.assertEquals(9988L, result.getData().get(Constants.ZERO).getGroupId());
        Assertions.assertEquals(Constants.ONE, result.getData().get(Constants.ZERO).getPipelines().size());
        Assertions.assertEquals("ct-modules-scm-测试环境", result.getData().get(Constants.ZERO).getPipelines().get(Constants.ZERO).getPipelineName());
    }
}
