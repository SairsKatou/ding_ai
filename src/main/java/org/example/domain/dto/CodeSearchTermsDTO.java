package org.example.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 代码检索词推断结果：业务实体词用于定位文件，动作词用于定位方法
 *
 * @author zeymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeSearchTermsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务实体词，如 reimbursement、allocation_order
     */
    @Builder.Default
    private Set<String> entityTokens = new LinkedHashSet<>();

    /**
     * 动作词，如 add、check、validate
     */
    @Builder.Default
    private Set<String> actionTokens = new LinkedHashSet<>();
}
