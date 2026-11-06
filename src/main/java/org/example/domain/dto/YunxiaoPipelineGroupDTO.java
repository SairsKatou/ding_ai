package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 云效流水线分组信息 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效流水线分组信息 DTO")
public class YunxiaoPipelineGroupDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线分组 ID
     */
    @ApiModelProperty(value = "流水线分组ID", example = "12345")
    private Long groupId;

    /**
     * 流水线分组名称
     */
    @ApiModelProperty(value = "流水线分组名称", example = "微服务集群分组")
    private String groupName;

    /**
     * 创建人账号 ID
     */
    @ApiModelProperty(value = "创建人账号ID", example = "user-60b123")
    private String creatorAccountId;

    /**
     * 创建时间
     */
    @ApiModelProperty(value = "创建时间", example = "2026-10-20 10:00:00")
    private String createTime;

    /**
     * 该分组下的具体流水线列表
     */
    @ApiModelProperty(value = "该分组下的具体流水线列表")
    private List<YunxiaoPipelineDTO> pipelines;
}
