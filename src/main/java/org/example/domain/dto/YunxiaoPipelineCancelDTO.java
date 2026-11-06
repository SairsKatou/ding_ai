package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效流水线运行取消结果 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线运行取消结果 DTO")
public class YunxiaoPipelineCancelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线 ID
     */
    @ApiModelProperty(value = "流水线ID", example = "123456")
    private Long pipelineId;

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
     * 流水线运行批次 ID
     */
    @ApiModelProperty(value = "流水线运行批次ID", example = "987654321")
    private Long pipelineRunId;

    /**
     * 涉及分支名称
     */
    @ApiModelProperty(value = "分支名称", example = "feature/order-v1")
    private String branchName;

    /**
     * 所属发布环境
     */
    @ApiModelProperty(value = "发布环境", example = "test")
    private String env;

    /**
     * 取消是否成功
     */
    @ApiModelProperty(value = "取消是否成功", example = "true")
    private Boolean success;

    /**
     * 执行说明或提示信息
     */
    @ApiModelProperty(value = "执行说明信息", example = "流水线运行实例已成功终止")
    private String message;

    /**
     * 取消时间
     */
    @ApiModelProperty(value = "取消时间", example = "2026-11-06 13:45:00")
    private String cancelTime;
}
