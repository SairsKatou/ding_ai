package org.example.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分支代码扫描群聊指令解析结果传输对象
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YunxiaoCodeScanInstructionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标项目名/仓库ID
     */
    private String repoName;

    /**
     * 目标分支名
     */
    private String branch;

    /**
     * 用户提问/分析诉求
     */
    private String question;
}
