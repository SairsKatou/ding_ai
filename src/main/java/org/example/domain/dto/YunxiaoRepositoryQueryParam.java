package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 云效代码库查询请求参数
 *
 * @author zeymo
 */
@Data
@ApiModel(description = "云效代码库查询请求参数")
public class YunxiaoRepositoryQueryParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 搜索关键词（代码库名称或路径）
     */
    @ApiModelProperty(value = "搜索关键词", example = "order")
    private String search;

    /**
     * 页码，从 1 开始
     */
    @ApiModelProperty(value = "页码，从1开始", example = "1")
    private Long page;

    /**
     * 每页数量，默认 20，最大 100
     */
    @ApiModelProperty(value = "每页数量", example = "20")
    private Long perPage;

    /**
     * 是否只查询已归档项目
     */
    @ApiModelProperty(value = "是否已归档", example = "false")
    private Boolean archived;

    /**
     * 是否同时获取各个代码库的分支列表
     */
    @ApiModelProperty(value = "是否同时查询对应分支列表", example = "true")
    private Boolean withBranches;
}
