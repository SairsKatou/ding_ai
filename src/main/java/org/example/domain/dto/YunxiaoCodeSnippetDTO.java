package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 命中的代码片段数据对象
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "命中的代码片段数据")
public class YunxiaoCodeSnippetDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件相对路径
     */
    @ApiModelProperty(value = "文件相对路径")
    private String filePath;

    /**
     * 代码片段内容
     */
    @ApiModelProperty(value = "代码片段内容")
    private String snippet;

    /**
     * 匹配原因说明 (路径命中 / 内容命中)
     */
    @ApiModelProperty(value = "匹配原因说明")
    private String matchReason;
}
