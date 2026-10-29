package org.example.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 深度扫描 Agent 初始线索：由本地检索预先得到，Agent 第 1 轮即可直接读取核心方法
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeAgentSeedDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 英文检索词（按专属度降序），如 reimbursement、actual_reimbursement
     */
    @Builder.Default
    private List<String> englishTokens = new ArrayList<>();

    /**
     * 动作词，如 add、check
     */
    @Builder.Default
    private List<String> actionTokens = new ArrayList<>();

    /**
     * 相关度最高的文件相对路径
     */
    @Builder.Default
    private List<String> topFiles = new ArrayList<>();

    /**
     * 相关度最高的 Java 文件相对路径
     */
    private String topFilePath;

    /**
     * 该 Java 文件的方法列表，如 addReimbursement (行 423-484)
     */
    @Builder.Default
    private List<String> topFileMethods = new ArrayList<>();
}
