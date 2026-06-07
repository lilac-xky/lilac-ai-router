package com.lilac.utils;

import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;

/**
 * 抛异常工具类
 */
public class ThrowUtils {

    /**
     * 条件成立则抛异常
     *
     * @param condition        条件
     * @param runtimeException 异常
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * 条件成立则抛异常
     *
     * @param condition     条件
     * @param errorCodeEnum 错误码枚举
     */
    public static void throwIf(boolean condition, HttpsCodeEnum errorCodeEnum) {
        throwIf(condition, new BusinessException(errorCodeEnum));
    }

    /**
     * 条件成立则抛异常
     *
     * @param condition     条件
     * @param errorCodeEnum 错误码枚举
     * @param message       错误信息
     */
    public static void throwIf(boolean condition, HttpsCodeEnum errorCodeEnum, String message) {
        throwIf(condition, new BusinessException(errorCodeEnum, message));
    }
}
