package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效代码库文件树节点数据传输对象
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效代码库文件树节点数据")
public class YunxiaoCodeFileTreeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件或目录 ID
     */
    @ApiModelProperty(value = "文件或目录 ID")
    private String id;

    /**
     * 文件名称
     */
    @ApiModelProperty(value = "文件名称")
    private String name;

    /**
     * 文件或目录相对路径
     */
    @ApiModelProperty(value = "文件或目录相对路径")
    private String path;

    /**
     * 节点类型 (blob: 文件, tree: 目录)
     */
    @ApiModelProperty(value = "节点类型 (blob: 文件, tree: 目录)")
    private String type;

    /**
     * 文件模式
     */
    @ApiModelProperty(value = "文件模式")
    private String mode;
}
