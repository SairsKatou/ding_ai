package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效代码库分支信息 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效代码库分支信息")
public class YunxiaoBranchDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分支名称
     */
    @ApiModelProperty(value = "分支名称", example = "master")
    private String name;

    /**
     * 是否受保护分支
     */
    @ApiModelProperty(value = "是否受保护分支", example = "true")
    private Boolean isProtected;

    /**
     * 最新提交 Commit ID
     */
    @ApiModelProperty(value = "最新提交Commit ID", example = "49ddb1677652aa9d2616a5bf4fc460107463e8a7")
    private String commitId;

    /**
     * 最新提交 Commit 消息
     */
    @ApiModelProperty(value = "最新提交消息", example = "feat: 初始化项目")
    private String commitMessage;

    /**
     * 提交人姓名
     */
    @ApiModelProperty(value = "提交人姓名", example = "张三")
    private String commitAuthorName;

    /**
     * 提交人邮箱
     */
    @ApiModelProperty(value = "提交人邮箱", example = "zhangsan@example.com")
    private String commitAuthorEmail;

    /**
     * 提交时间
     */
    @ApiModelProperty(value = "提交时间", example = "2026-10-19T10:00:00Z")
    private String committedDate;
}
