package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效流水线运行取消请求参数
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线运行取消请求参数")
public class YunxiaoPipelineCancelParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线 ID (可选，若为空则自动通过仓库、分组或默认流水线匹配)
     */
    @ApiModelProperty(value = "流水线ID", example = "123456")
    private Long pipelineId;

    /**
     * 流水线分组 ID (可选，指定所属流水线分组)
     */
    @ApiModelProperty(value = "流水线分组ID", example = "12345")
    private Long pipelineGroupId;

    /**
     * 流水线分组名称 (可选，根据名称智能定位分组)
     */
    @ApiModelProperty(value = "流水线分组名称", example = "微服务集群分组")
    private String pipelineGroupName;

    /**
     * 流水线名称 (可选)
     */
    @ApiModelProperty(value = "流水线名称", example = "ct-modules-file-开发环境")
    private String pipelineName;

    /**
     * 流水线运行批次 ID (可选，若为空则根据分支自动查找运行中的实例)
     */
    @ApiModelProperty(value = "流水线运行批次ID", example = "987654321")
    private Long pipelineRunId;

    /**
     * 代码仓库ID或仓库名称 (可选，默认 changtai-cloud)
     */
    @ApiModelProperty(value = "代码仓库ID或名称", example = "changtai-cloud")
    private String repositoryId;

    /**
     * 待取消发布的分支名称 (可选)
     */
    @ApiModelProperty(value = "待取消发布的分支名称", example = "feature/order-v1")
    private String branchName;

    /**
     * 目标环境 (可选，开发 dev / 测试 test / 生产 prod 或自定义)
     */
    @ApiModelProperty(value = "目标环境", example = "test")
    private String env;
}
