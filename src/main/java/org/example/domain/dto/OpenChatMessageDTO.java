package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大模型对话消息 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "大模型对话单条消息")
public class OpenChatMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 角色：system / user / assistant
     */
    @ApiModelProperty(value = "消息角色 (system/user/assistant)", example = "user")
    private String role;

    /**
     * 消息内容
     */
    @ApiModelProperty(value = "消息内容", example = "请用Java写一个二分查找")
    private String content;
}
