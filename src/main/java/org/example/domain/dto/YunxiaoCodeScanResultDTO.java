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
 * 分支代码扫描与大模型分析结果数据传输对象
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "分支代码扫描与大模型分析结果")
public class YunxiaoCodeScanResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 云效代码库 ID
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
     * 用户提问
     */
    @ApiModelProperty(value = "用户提问")
    private String question;

    /**
     * 实际匹配到的相关文件数量
     */
    @ApiModelProperty(value = "匹配到的相关文件数量")
    private Integer matchedFileCount;

    /**
     * 命中的文件相对路径列表
     */
    @ApiModelProperty(value = "命中的文件相对路径列表")
    private List<String> matchedFiles;

    /**
     * 大模型生成的深度审计与梳理分析报告（Markdown 格式）
     */
    @ApiModelProperty(value = "大模型分析报告 (Markdown)")
    private String analysisReport;
}
