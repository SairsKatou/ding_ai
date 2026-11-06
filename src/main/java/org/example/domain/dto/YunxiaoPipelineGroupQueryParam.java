package org.example.domain.dto;

import com.ct.common.core.web.domain.PageDomain;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 云效流水线分组查询参数
 *
 * @author zeymo
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(description = "云效流水线分组查询参数")
public class YunxiaoPipelineGroupQueryParam extends PageDomain {

    private static final long serialVersionUID = 1L;

    /**
     * 流水线分组名称关键词
     */
    @ApiModelProperty(value = "流水线分组名称关键词", example = "微服务")
    private String groupName;
}
