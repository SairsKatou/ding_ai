package org.example.domain.dto;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 大模型函数工具定义 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "大模型函数工具定义")
public class OpenChatFunctionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 函数名称
     */
    @ApiModelProperty(value = "函数名称", example = "search_files")
    private String name;

    /**
     * 函数功能描述
     */
    @ApiModelProperty(value = "函数描述", example = "在代码库中根据路径或类名模式模糊查找文件")
    private String description;

    /**
     * 函数入参结构定义 (JSON Schema)
     */
    @ApiModelProperty(value = "函数参数Schema定义")
    private JSONObject parameters;
}
