package org.example.domain.dto;

import com.ct.common.core.web.domain.PageDomain;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 云效流水线查询请求参数
 *
 * @author zeymo
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(description = "云效流水线查询请求参数")
public class YunxiaoPipelineQueryParam extends PageDomain {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线名称模糊搜索关键词
     */
    @ApiModelProperty(value = "流水线名称搜索关键词", example = "changtai")
    private String pipelineName;

    /**
     * 流水线分组 ID
     */
    @ApiModelProperty(value = "流水线分组ID", example = "12345")
    private Long pipelineGroupId;
}
