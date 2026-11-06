package com.ct.common.core.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 分支发布模式枚举
 *
 * @author zeymo
 */
@Getter
public enum BranchReleaseModeEnum {

    /**
     * 云效 Flow 流水线触发模式
     */
    PIPELINE_FLOW("PIPELINE_FLOW", "云效Flow流水线触发模式"),

    /**
     * 代码合并模式
     */
    CODE_MERGE("CODE_MERGE", "代码分支合并模式");

    /**
     * 模式编码
     */
    private final String code;

    /**
     * 模式说明
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code 模式编码
     * @param description 模式说明
     */
    BranchReleaseModeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码解析枚举
     *
     * @param code 模式编码
     * @return 对应的枚举实例，未找到返回 null
     */
    public static BranchReleaseModeEnum getByCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (BranchReleaseModeEnum modeEnum : values()) {
            if (modeEnum.getCode().equalsIgnoreCase(code.trim())) {
                return modeEnum;
            }
        }
        return null;
    }
}
