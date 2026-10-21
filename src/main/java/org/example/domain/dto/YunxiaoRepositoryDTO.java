package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 云效项目(代码库)信息 DTO
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效项目(代码库)信息")
public class YunxiaoRepositoryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 代码库 ID
     */
    @ApiModelProperty(value = "代码库ID", example = "100234")
    private Long id;

    /**
     * 代码库名称
     */
    @ApiModelProperty(value = "代码库名称", example = "order-service")
    private String name;

    /**
     * 带命名空间的代码库名称
     */
    @ApiModelProperty(value = "带命名空间的代码库名称", example = "backend/order-service")
    private String nameWithNamespace;

    /**
     * 代码库路径
     */
    @ApiModelProperty(value = "代码库路径", example = "order-service")
    private String path;

    /**
     * 带命名空间的代码库路径
     */
    @ApiModelProperty(value = "带命名空间的代码库路径", example = "backend/order-service")
    private String pathWithNamespace;

    /**
     * Web 端访问地址
     */
    @ApiModelProperty(value = "Web访问地址", example = "https://codeup.aliyun.com/60b/backend/order-service")
    private String webUrl;

    /**
     * HTTP(S) 克隆地址
     */
    @ApiModelProperty(value = "HTTP克隆地址", example = "https://codeup.aliyun.com/60b/backend/order-service.git")
    private String httpUrlToRepo;

    /**
     * SSH 克隆地址
     */
    @ApiModelProperty(value = "SSH克隆地址", example = "git@codeup.aliyun.com:60b/backend/order-service.git")
    private String sshUrlToRepo;

    /**
     * 代码库描述
     */
    @ApiModelProperty(value = "代码库描述", example = "订单服务核心仓库")
    private String description;

    /**
     * 可见性级别
     */
    @ApiModelProperty(value = "可见性级别", example = "private")
    private String visibilityLevel;

    /**
     * 最后活动时间
     */
    @ApiModelProperty(value = "最后活动时间", example = "2026-10-19T11:20:00Z")
    private String lastActivityAt;

    /**
     * 对应分支列表
     */
    @ApiModelProperty(value = "对应分支列表")
    private List<YunxiaoBranchDTO> branches;
}
