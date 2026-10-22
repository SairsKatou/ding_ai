package org.example.domain.dto;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

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
     * 角色：system / user / assistant / tool
     */
    @ApiModelProperty(value = "消息角色 (system/user/assistant/tool)", example = "user")
    private String role;

    /**
     * 消息内容
     */
    @ApiModelProperty(value = "消息内容", example = "请用Java写一个二分查找")
    private String content;

    /**
     * 函数/工具名称 (当 role 为 tool 或 function 时指定)
     */
    @ApiModelProperty(value = "工具名称", example = "search_files")
    private String name;

    /**
     * 对应发起的工具调用 ID (当 role 为 tool 时必填)
     */
    @JSONField(name = "tool_call_id")
    @JsonProperty("tool_call_id")
    @ApiModelProperty(value = "对应工具调用ID", example = "call_abc123")
    private String toolCallId;

    /**
     * 大模型响应时发起的工具调用列表 (当 role 为 assistant 且触发工具调用时返回)
     */
    @JSONField(name = "tool_calls")
    @JsonProperty("tool_calls")
    @ApiModelProperty(value = "大模型发起的工具调用列表")
    private List<OpenChatToolCallDTO> toolCalls;
}
