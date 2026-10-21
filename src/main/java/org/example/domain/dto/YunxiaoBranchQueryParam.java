package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 云效代码库分支查询请求参数
 *
 * @author zeymo
 */
@Data
@ApiModel(description = "云效代码库分支查询请求参数")
public class YunxiaoBranchQueryParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 搜索关键词（分支名称过滤）
     */
    @ApiModelProperty(value = "分支搜索关键词", example = "feature")
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
    private Long pageSize;

    /**
     * 排序规则，可选值：updated_desc / updated_asc
     */
    @ApiModelProperty(value = "排序规则", example = "updated_desc")
    private String sort;
}
