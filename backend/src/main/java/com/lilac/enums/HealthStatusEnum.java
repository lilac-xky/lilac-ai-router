package com.lilac.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 健康状态枚举
 */
@Getter
public enum HealthStatusEnum {

    HEALTHY("healthy", "健康"),
    DEGRADED("degraded", "降级"),
    UNHEALTHY("unhealthy", "不健康"),
    UNKNOWN("unknown", "未知");

    private final String value;

    private final String text;

    HealthStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值
     * @return 枚举，未匹配返回 null
     */
    public static HealthStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (HealthStatusEnum anEnum : HealthStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    /**
     * 获取所有枚举值列表
     *
     * @return 枚举值列表
     */
    public static List<String> getValues() {
        return Arrays.stream(values()).map(item -> item.value).collect(Collectors.toList());
    }
}
