package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大模型函数调用入参详情 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "大模型函数调用入参详情")
public class OpenChatFunctionCallDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 要调用的函数名
     */
    @ApiModelProperty(value = "函数名称", example = "search_files")
    private String name;

    /**
     * 函数入参 JSON 字符串
     */
    @ApiModelProperty(value = "函数入参JSON文本", example = "{\"pattern\":\"AllocationOrder\"}")
    private String arguments;
}
