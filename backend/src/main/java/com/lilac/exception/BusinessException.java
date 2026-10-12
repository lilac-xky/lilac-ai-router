package com.lilac.exception;

import com.lilac.enums.HttpsCodeEnum;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;

    // 主构造函数：接收枚举
    public BusinessException(HttpsCodeEnum httpsCodeEnum) {
        super(httpsCodeEnum.getMsg());
        this.code = httpsCodeEnum.getCode();
    }

    // 可选：接收枚举和自定义消息
    public BusinessException(HttpsCodeEnum httpsCodeEnum, String customMessage) {
        super(customMessage);
        this.code = httpsCodeEnum.getCode();
    }

    // 可选：接收枚举、自定义消息和异常
    public BusinessException(HttpsCodeEnum httpsCodeEnum, String customMessage, Throwable cause) {
        super(customMessage, cause);
        this.code = httpsCodeEnum.getCode();
    }
}