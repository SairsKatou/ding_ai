package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效代码库文件内容数据传输对象
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效代码库文件内容数据")
public class YunxiaoCodeFileBlobDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件名称
     */
    @ApiModelProperty(value = "文件名称")
    private String fileName;

    /**
     * 文件完整相对路径
     */
    @ApiModelProperty(value = "文件完整相对路径")
    private String filePath;

    /**
     * 文件内容（通常为 Base64 编码）
     */
    @ApiModelProperty(value = "文件内容 Base64 字符串")
    private String content;

    /**
     * 编码方式，如 base64
     */
    @ApiModelProperty(value = "编码方式")
    private String encoding;

    /**
     * 文件大小 (字节)
     */
    @ApiModelProperty(value = "文件大小")
    private Long size;

    /**
     * 分支或引用名称
     */
    @ApiModelProperty(value = "分支或引用名称")
    private String ref;

    /**
     * 提交 ID
     */
    @ApiModelProperty(value = "提交 ID")
    private String commitId;
}
