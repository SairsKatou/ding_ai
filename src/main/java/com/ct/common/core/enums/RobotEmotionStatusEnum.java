package com.ct.common.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 钉钉机器人消息表情表态状态枚举
 *
 * @author zeymo
 */
@Getter
@AllArgsConstructor
public enum RobotEmotionStatusEnum {

    /**
     * 思考中状态
     */
    THINKING("thinking", "🤔思考中", "2659900", "im_bg_1", "机器人正在思考或排查处理中"),

    /**
     * 完成状态
     */
    COMPLETED("completed", "📦完成", "2659900", "im_bg_1", "机器人已完成处理并回复");

    /**
     * 状态编码
     */
    private final String code;

    /**
     * 展示的表情文案
     */
    private final String emotionText;

    /**
     * 钉钉表情 ID
     */
    private final String emotionId;

    /**
     * 钉钉文字表情背景 ID
     */
    private final String backgroundId;

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 根据状态编码解析枚举
     *
     * @param code 状态编码
     * @return 对应的枚举，未匹配返回 null
     */
    public static RobotEmotionStatusEnum fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (RobotEmotionStatusEnum statusEnum : values()) {
            if (StringUtils.equalsIgnoreCase(statusEnum.getCode(), code.trim())) {
                return statusEnum;
            }
        }
        return null;
    }
}
