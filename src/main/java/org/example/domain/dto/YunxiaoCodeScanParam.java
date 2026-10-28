package org.example.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 云效分支代码扫描与大模型分析请求参数
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "云效分支代码大模型扫描请求参数")
public class YunxiaoCodeScanParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 云效代码库 ID 或代码库名称/路径（如 6120951 或 changtai-cloud）
     */
    @ApiModelProperty(value = "代码库 ID 或代码库名称", required = true, example = "changtai-cloud")
    private String repositoryId;

    /**
     * 分支名称（如 dev、master、或具体需求分支）
     */
    @ApiModelProperty(value = "分支名称", required = true, example = "dev")
    private String branch;

    /**
     * 针对该分支代码的具体提问与分析诉求（如：梳理实报使用的地方）
     */
    @ApiModelProperty(value = "分析提问或诉求", required = true, example = "梳理实报使用的地方")
    private String question;

    /**
     * 检索关键词（可选；若未指定则自动从提问中提取）
     */
    @ApiModelProperty(value = "检索关键词（可选）", example = "实报")
    private String keyword;

    /**
     * 参与扫描分析的最大代码文件数（本地模式默认 50、最大 100，远程模式最大 30）
     */
    @ApiModelProperty(value = "最大分析文件数（本地模式默认 50、最大 100，远程模式最大 30）", example = "50")
    private Integer maxFiles;

    /**
     * 是否启用多轮 Agent 深度代码探查模式 (Tool Calling)
     */
    @ApiModelProperty(value = "是否启用多轮 Agent 深度扫描", example = "false")
    private Boolean deepScan;
}
