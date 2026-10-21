package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效代码库创建分支请求参数
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效代码库创建分支请求参数")
public class YunxiaoBranchCreateParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 代码库 ID 或代码库名称/路径
     */
    @ApiModelProperty(value = "代码库ID或名称", required = true, example = "6120951")
    private String repositoryId;

    /**
     * 新建分支名称
     */
    @ApiModelProperty(value = "新分支名称", required = true, example = "feature/20261019-login")
    private String branchName;

    /**
     * 源分支名称、Tag 或 Commit ID
     */
    @ApiModelProperty(value = "源分支名或Commit", required = true, example = "master")
    private String ref;
}
