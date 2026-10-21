package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效本地代码库克隆缓存元数据
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效本地代码库克隆缓存元数据")
public class YunxiaoRepoCacheMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 代码库 ID
     */
    @ApiModelProperty(value = "代码库 ID")
    private String repositoryId;

    /**
     * 代码库名称
     */
    @ApiModelProperty(value = "代码库名称")
    private String repositoryName;

    /**
     * 分支名称
     */
    @ApiModelProperty(value = "分支名称")
    private String branch;

    /**
     * 本地克隆绝对路径
     */
    @ApiModelProperty(value = "本地克隆绝对路径")
    private String cloneDir;

    /**
     * 缓存创建/更新时间戳 (毫秒)
     */
    @ApiModelProperty(value = "缓存创建时间戳")
    private Long cachedAt;

    /**
     * 缓存过期时间戳 (毫秒)
     */
    @ApiModelProperty(value = "缓存过期时间戳")
    private Long expireAt;
}
