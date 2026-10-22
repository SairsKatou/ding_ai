package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大模型发起的单个工具调用指令 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "大模型工具调用指令")
public class OpenChatToolCallDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工具调用唯一标识 ID
     */
    @ApiModelProperty(value = "调用唯一ID", example = "call_abc123")
    private String id;

    /**
     * 调用类型，通常为 function
     */
    @ApiModelProperty(value = "调用类型", example = "function")
    private String type;

    /**
     * 目标函数与入参
     */
    @ApiModelProperty(value = "目标函数与入参详情")
    private OpenChatFunctionCallDTO function;
}
