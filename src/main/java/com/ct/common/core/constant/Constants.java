package com.ct.common.core.constant;

/**
 * 通用常量信息
 *
 * @author zeymo
 */
public class Constants {

    /**
     * 成功标记
     */
    public static final Integer SUCCESS = 200;

    /**
     * 失败标记
     */
    public static final Integer FAIL = 500;

    /**
     * 数字 0
     */
    public static final Integer ZERO = 0;

    /**
     * 数字 1
     */
    public static final Integer ONE = 1;

    /**
     * 数字 2
     */
    public static final Integer TWO = 2;

    /**
     * 数字 3 (单模型最大重试次数 / 模型最大切换数)
     */
    public static final Integer THREE = 3;

    /**
     * 数字 4
     */
    public static final Integer FOUR = 4;

    /**
     * 数字 5
     */
    public static final Integer FIVE = 5;

    /**
     * 数字 8 (线程池核心线程数)
     */
    public static final Integer EIGHT = 8;

    /**
     * 数字 9 (全部模型累计最大重试次数)
     */
    public static final Integer NINE = 9;

    /**
     * 数字 10 (默认单次扫描最大代码文件数)
     */
    public static final Integer TEN = 10;

    /**
     * 数字 16 (线程池最大线程数)
     */
    public static final Integer SIXTEEN = 16;

    /**
     * 数字 20
     */
    public static final Integer TWENTY = 20;

    /**
     * 数字 30 (远程 OpenAPI 模式最大分析文件数)
     */
    public static final Integer THIRTY = 30;

    /**
     * 数字 50
     */
    public static final Integer FIFTY = 50;

    /**
     * 数字 60 (线程存活秒数)
     */
    public static final Integer SIXTY = 60;

    /**
     * 数字 100
     */
    public static final Integer HUNDRED = 100;

    /**
     * 数字 200 (线程池等待队列容量)
     */
    public static final Integer TWO_HUNDRED = 200;

    /**
     * 数字 500 (本地 Git 浅克隆模式最大分析文件数)
     */
    public static final Integer FIVE_HUNDRED = 500;

    /**
     * 数字 1000 (最大缓存条数)
     */
    public static final Integer THOUSAND = 1000;

    /**
     * 数字 2048 (Token 最大生成长度)
     */
    public static final Integer TWO_THOUSAND_FORTY_EIGHT = 2048;

    /**
     * 数字 3000
     */
    public static final Integer THREE_THOUSAND = 3000;

    /**
     * 数字 4000 (单文件截断最大长度)
     */
    public static final Integer FOUR_THOUSAND = 4000;

    /**
     * 数字 5000 (超时时间毫秒)
     */
    public static final Integer FIVE_THOUSAND = 5000;

    /**
     * 数字 10000
     */
    public static final Integer TEN_THOUSAND = 10000;

    /**
     * 数字 60000 (AI 请求超时时间毫秒)
     */
    public static final Integer SIXTY_THOUSAND = 60000;

    /**
     * 数字 180000 (AI 读取超时时间毫秒，即 3 分钟)
     */
    public static final Integer ONE_HUNDRED_EIGHTY_THOUSAND = 180000;

    /**
     * 数字 300000 (AI 最大读取超时时间毫秒，即 5 分钟)
     */
    public static final Integer THREE_HUNDRED_THOUSAND = 300000;

    /**
     * Long 0
     */
    public static final Long ZERO_LONG = 0L;

    /**
     * Long 1
     */
    public static final Long ONE_LONG = 1L;

    /**
     * Long 20
     */
    public static final Long TWENTY_LONG = 20L;

    /**
     * Long 100
     */
    public static final Long HUNDRED_LONG = 100L;

    /**
     * 10 分钟毫秒数 (10 * 60 * 1000 = 600,000L，用于消息防重有效期)
     */
    public static final Long TEN_MINUTES_MILLIS = 600000L;

    /**
     * 2 小时毫秒数 (2 * 60 * 60 * 1000 = 7,200,000L)
     */
    public static final Long TWO_HOURS_MILLIS = 7200000L;

    /**
     * 默认分支名称 dev
     */
    public static final String DEFAULT_DEV_BRANCH = "dev";

    /**
     * UTF-8 字符集
     */
    public static final String UTF8 = "UTF-8";
}
