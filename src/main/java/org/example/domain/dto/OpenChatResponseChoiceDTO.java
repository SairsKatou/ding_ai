package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OpenCode 对话响应选项 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "OpenCode 对话回复候选项")
public class OpenChatResponseChoiceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 选项索引
     */
    @ApiModelProperty(value = "索引", example = "0")
    private Integer index;

    /**
     * 回复消息
     */
    @ApiModelProperty(value = "生成的回复消息")
    private OpenChatMessageDTO message;

    /**
     * 结束原因
     */
    @ApiModelProperty(value = "结束原因", example = "stop")
    private String finishReason;
}
