package com.lilac.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型状态枚举
 */
@Getter
public enum ModelStatusEnum {

    ACTIVE("active", "启用"),
    INACTIVE("inactive", "停用"),
    DEPRECATED("deprecated", "已弃用");

    private final String value;

    private final String text;

    ModelStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值
     * @return 枚举，未匹配返回 null
     */
    public static ModelStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ModelStatusEnum anEnum : ModelStatusEnum.values()) {
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
