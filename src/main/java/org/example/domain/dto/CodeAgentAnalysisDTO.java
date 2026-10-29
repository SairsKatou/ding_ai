package org.example.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 带工具调用的大模型分析结果
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeAgentAnalysisDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分析报告
     */
    private String report;

    /**
     * 通过工具读取过的文件（仓库相对路径）
     */
    @Builder.Default
    private List<String> readFiles = new ArrayList<>();

    /**
     * 工具输出中出现过的文件（仓库相对路径）
     */
    @Builder.Default
    private List<String> referencedFiles = new ArrayList<>();

    /**
     * 实际大模型调用轮数
     */
    private int turns;

    /**
     * 成功的工具调用次数
     */
    private int successfulToolCalls;
}
