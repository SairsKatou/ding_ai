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
 * OpenCode 对话完成响应 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "OpenCode 对话完成响应")
public class OpenChatResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应 ID
     */
    @ApiModelProperty(value = "请求唯一ID", example = "chatcmpl-123")
    private String id;

    /**
     * 对象类型
     */
    @ApiModelProperty(value = "对象类型", example = "chat.completion")
    private String object;

    /**
     * 创建时间戳
     */
    @ApiModelProperty(value = "创建时间戳", example = "1677652288")
    private Long created;

    /**
     * 所使用的模型
     */
    @ApiModelProperty(value = "模型名称", example = "kimi-k2.6")
    private String model;

    /**
     * 生成的回复列表
     */
    @ApiModelProperty(value = "回复选项列表")
    private List<OpenChatResponseChoiceDTO> choices;
}
