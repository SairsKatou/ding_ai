package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效流水线运行结果 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线运行结果 DTO")
public class YunxiaoPipelineRunDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线运行实例批次 ID
     */
    @ApiModelProperty(value = "流水线运行批次ID", example = "987654321")
    private Long pipelineRunId;

    /**
     * 所属流水线 ID
     */
    @ApiModelProperty(value = "所属流水线ID", example = "123456")
    private Long pipelineId;

    /**
     * 流水线名称
     */
    @ApiModelProperty(value = "流水线名称", example = "changtai-cloud-pipeline")
    private String pipelineName;

    /**
     * 流水线所属分组 ID
     */
    @ApiModelProperty(value = "流水线所属分组ID", example = "12345")
    private Long pipelineGroupId;

    /**
     * 流水线所属分组名称
     */
    @ApiModelProperty(value = "流水线所属分组名称", example = "微服务集群分组")
    private String pipelineGroupName;

    /**
     * 运行/发布分支
     */
    @ApiModelProperty(value = "发布分支名称", example = "feature/order-v1")
    private String branchName;

    /**
     * 发布目标环境 (如 dev, test, prod 或自定义环境)
     */
    @ApiModelProperty(value = "发布目标环境", example = "test")
    private String env;

    /**
     * 运行状态编码 (如 RUNNING, SUCCESS, FAIL 等)
     */
    @ApiModelProperty(value = "运行状态编码", example = "RUNNING")
    private String status;

    /**
     * 状态中文描述
     */
    @ApiModelProperty(value = "状态中文描述", example = "运行中")
    private String statusDescription;

    /**
     * 触发时间
     */
    @ApiModelProperty(value = "触发时间", example = "2026-11-06 13:30:00")
    private String triggerTime;

    /**
     * 流水线运行详情跳转链接 (可选)
     */
    @ApiModelProperty(value = "流水线详情跳转链接", example = "https://flow.aliyun.com/pipelines/123456/builds/987654321")
    private String viewUrl;
}
