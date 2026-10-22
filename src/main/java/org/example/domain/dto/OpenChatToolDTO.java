package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大模型工具声明 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "大模型工具声明")
public class OpenChatToolDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工具类型，固定为 function
     */
    @ApiModelProperty(value = "工具类型", example = "function")
    private String type;

    /**
     * 具体函数定义
     */
    @ApiModelProperty(value = "函数详细定义")
    private OpenChatFunctionDTO function;
}
