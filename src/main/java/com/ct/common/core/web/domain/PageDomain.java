package com.ct.common.core.web.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页数据请求通用基类
 *
 * @author zeymo
 */
@Data
@ApiModel(description = "分页请求数据")
public class PageDomain implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前记录起始索引/页码
     */
    @ApiModelProperty(value = "页码，从1开始", example = "1")
    private Integer pageNum;

    /**
     * 每页显示记录数
     */
    @ApiModelProperty(value = "每页显示记录数", example = "20")
    private Integer pageSize;

    /**
     * 排序列
     */
    @ApiModelProperty(value = "排序列", example = "create_time")
    private String orderByColumn;

    /**
     * 排序的方向desc或者asc
     */
    @ApiModelProperty(value = "排序的方向desc或者asc", example = "desc")
    private String isAsc;
}
