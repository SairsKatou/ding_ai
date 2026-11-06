package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效流水线信息 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线信息 DTO")
public class YunxiaoPipelineDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线 ID
     */
    @ApiModelProperty(value = "流水线ID", example = "123456")
    private Long pipelineId;

    /**
     * 流水线名称
     */
    @ApiModelProperty(value = "流水线名称", example = "changtai-cloud-prod-pipeline")
    private String pipelineName;

    /**
     * 所属流水线分组 ID
     */
    @ApiModelProperty(value = "所属流水线分组ID", example = "12345")
    private Long groupId;

    /**
     * 创建人账号 ID
     */
    @ApiModelProperty(value = "创建人账号ID", example = "60bxxxxx")
    private String creatorAccountId;

    /**
     * 环境名称
     */
    @ApiModelProperty(value = "环境名称", example = "prod")
    private String envName;

    /**
     * 创建时间
     */
    @ApiModelProperty(value = "创建时间", example = "2026-10-20 10:00:00")
    private String createTime;
}
