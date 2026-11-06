package com.ct.common.core.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用枚举映射管理器
 *
 * @author zeymo
 */
public class EnumMappingManager {

    /**
     * 枚举注册表映射字典
     */
    private static final Map<String, List<Map<String, String>>> ENUM_REGISTRY = new HashMap<>();

    static {
        // 注册流水线运行状态枚举
        registerEnum(PipelineRunStatusEnum.class.getSimpleName(), buildPipelineRunStatusItems());
        // 注册分支发布模式枚举
        registerEnum(BranchReleaseModeEnum.class.getSimpleName(), buildBranchReleaseModeItems());
        // 注册发布环境枚举
        registerEnum(ReleaseEnvironmentEnum.class.getSimpleName(), buildReleaseEnvironmentItems());
    }

    /**
     * 注册枚举项信息
     *
     * @param enumName 枚举名称
     * @param items    枚举项映射列表
     */
    public static void registerEnum(String enumName, List<Map<String, String>> items) {
        ENUM_REGISTRY.put(enumName, items);
    }

    /**
     * 获取指定枚举类型的项列表
     *
     * @param enumName 枚举名称
     * @return 枚举项字典列表
     */
    public static List<Map<String, String>> getEnumItems(String enumName) {
        if (StringUtils.isBlank(enumName)) {
            return Collections.emptyList();
        }
        return ENUM_REGISTRY.getOrDefault(enumName, Collections.emptyList());
    }

    /**
     * 构建流水线运行状态枚举项列表
     *
     * @return 映射列表
     */
    private static List<Map<String, String>> buildPipelineRunStatusItems() {
        List<Map<String, String>> list = new ArrayList<>();
        for (PipelineRunStatusEnum statusEnum : PipelineRunStatusEnum.values()) {
            Map<String, String> item = new HashMap<>();
            item.put("code", statusEnum.getCode());
            item.put("description", statusEnum.getDescription());
            list.add(item);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 构建分支发布模式枚举项列表
     *
     * @return 映射列表
     */
    private static List<Map<String, String>> buildBranchReleaseModeItems() {
        List<Map<String, String>> list = new ArrayList<>();
        for (BranchReleaseModeEnum modeEnum : BranchReleaseModeEnum.values()) {
            Map<String, String> item = new HashMap<>();
            item.put("code", modeEnum.getCode());
            item.put("description", modeEnum.getDescription());
            list.add(item);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 构建发布环境枚举项列表
     *
     * @return 映射列表
     */
    private static List<Map<String, String>> buildReleaseEnvironmentItems() {
        List<Map<String, String>> list = new ArrayList<>();
        for (ReleaseEnvironmentEnum envEnum : ReleaseEnvironmentEnum.values()) {
            Map<String, String> item = new HashMap<>();
            item.put("code", envEnum.getCode());
            item.put("description", envEnum.getDescription());
            list.add(item);
        }
        return Collections.unmodifiableList(list);
    }
}
