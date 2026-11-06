package com.ct.common.core.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 云效流水线运行状态枚举
 *
 * @author zeymo
 */
@Getter
public enum PipelineRunStatusEnum {

    /**
     * 运行中
     */
    RUNNING("RUNNING", "运行中"),

    /**
     * 运行成功
     */
    SUCCESS("SUCCESS", "运行成功"),

    /**
     * 运行失败
     */
    FAIL("FAIL", "运行失败"),

    /**
     * 已取消
     */
    CANCELED("CANCELED", "已取消"),

    /**
     * 等待中
     */
    WAITING("WAITING", "等待调度中");

    /**
     * 状态编码
     */
    private final String code;

    /**
     * 状态说明
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code 状态编码
     * @param description 状态说明
     */
    PipelineRunStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码解析枚举
     *
     * @param code 状态编码
     * @return 对应的枚举实例，未找到返回 null
     */
    public static PipelineRunStatusEnum getByCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (PipelineRunStatusEnum statusEnum : values()) {
            if (statusEnum.getCode().equalsIgnoreCase(code.trim())) {
                return statusEnum;
            }
        }
        return null;
    }
}
