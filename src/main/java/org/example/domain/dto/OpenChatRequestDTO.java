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
 * OpenCode / OpenAI 对话补全请求 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "OpenCode 对话请求参数")
public class OpenChatRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模型标识
     */
    @ApiModelProperty(value = "模型标识", example = "kimi-k2.6")
    private String model;

    /**
     * 对话消息列表
     */
    @ApiModelProperty(value = "消息列表")
    private List<OpenChatMessageDTO> messages;

    /**
     * 采样温度
     */
    @ApiModelProperty(value = "采样温度", example = "0.7")
    private Double temperature;

    /**
     * 最大回复 Token 数量
     */
    @ApiModelProperty(value = "最大Token数", example = "2048")
    private Integer maxTokens;

    /**
     * 是否流式返回（默认 false）
     */
    @ApiModelProperty(value = "是否流式返回", example = "false")
    private Boolean stream;
}
