package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 云效流水线触发运行请求参数
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线触发运行请求参数")
public class YunxiaoPipelineRunParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 代码仓库ID或仓库名称 (可选，默认 changtai-cloud)
     */
    @ApiModelProperty(value = "代码仓库ID或名称", example = "changtai-cloud")
    private String repositoryId;

    /**
     * 待发布的分支名称 (必填)
     */
    @ApiModelProperty(value = "待发布的分支名称", required = true, example = "feature/order-v1")
    private String branchName;

    /**
     * 目标发布环境 (开发 dev / 测试 test / 预发 pre / 生产 prod，支持自定义环境，默认 test)
     */
    @ApiModelProperty(value = "目标发布环境", example = "test")
    private String env;

    /**
     * 流水线 ID (可选，若不传则自动匹配仓库、环境或分组)
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
     * 发布说明 / 运行备注
     */
    @ApiModelProperty(value = "发布说明备注", example = "钉钉群触发发布分支")
    private String comment;

    /**
     * 自定义环境变量映射 (可选)
     */
    @ApiModelProperty(value = "自定义环境变量映射")
    private Map<String, String> envs;
}
