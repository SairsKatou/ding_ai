package com.ct.common.core.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 分支发布与部署环境枚举
 *
 * @author zeymo
 */
@Getter
public enum ReleaseEnvironmentEnum {

    /**
     * 开发环境
     */
    DEV("dev", "开发环境"),

    /**
     * 测试环境
     */
    TEST("test", "测试环境"),

    /**
     * 预发环境
     */
    PRE("pre", "预发环境"),

    /**
     * 生产环境
     */
    PROD("prod", "生产环境");

    /**
     * 环境编码
     */
    private final String code;

    /**
     * 环境说明
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code 环境编码
     * @param description 环境说明
     */
    ReleaseEnvironmentEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取对应枚举
     *
     * @param code 环境编码
     * @return 枚举实例，未找到返回 null
     */
    public static ReleaseEnvironmentEnum getByCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (ReleaseEnvironmentEnum envEnum : values()) {
            if (envEnum.getCode().equalsIgnoreCase(code.trim())) {
                return envEnum;
            }
        }
        return null;
    }

    /**
     * 智能解析环境标识（支持中文别名与自定义环境名称）
     *
     * @param text 输入文本
     * @return 识别出的环境编码或自定义环境名称，若为空返回 null
     */
    public static String resolveEnvCode(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String trimmed = text.trim();
        // 匹配开发环境
        if ("dev".equalsIgnoreCase(trimmed) || "开发".equals(trimmed) || "开发环境".equals(trimmed)) {
            return DEV.getCode();
        }
        // 匹配测试环境
        if ("test".equalsIgnoreCase(trimmed) || "测试".equals(trimmed) || "测试环境".equals(trimmed) || "qa".equalsIgnoreCase(trimmed)) {
            return TEST.getCode();
        }
        // 匹配预发环境
        if ("pre".equalsIgnoreCase(trimmed) || "预发".equals(trimmed) || "预发布".equals(trimmed) || "staging".equalsIgnoreCase(trimmed)) {
            return PRE.getCode();
        }
        // 匹配生产环境
        if ("prod".equalsIgnoreCase(trimmed) || "prd".equalsIgnoreCase(trimmed) || "生产".equals(trimmed) || "生产环境".equals(trimmed) || "线上".equals(trimmed)) {
            return PROD.getCode();
        }
        // 支持自定义环境直接返回
        return trimmed;
    }
}
