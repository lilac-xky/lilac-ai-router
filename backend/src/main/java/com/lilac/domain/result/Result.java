package com.lilac.domain.result;

import com.alibaba.fastjson.annotation.JSONField;
import com.lilac.enums.HttpsCodeEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结果
 */
@Data
public class Result<T> implements Serializable {
    @JSONField(ordinal = 1)
    private Integer code;
    @JSONField(ordinal = 2)
    private String msg;
    @JSONField(ordinal = 3)
    private T data;

    private Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(HttpsCodeEnum.SUCCESS.getCode(), HttpsCodeEnum.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(HttpsCodeEnum httpsCodeEnum) {
        return new Result<>(httpsCodeEnum.getCode(), httpsCodeEnum.getMsg(), null);
    }

    public static <T> Result<T> error(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }

    public static <T> Result<T> error(HttpsCodeEnum httpsCodeEnum, String customMessage) {
        return new Result<>(httpsCodeEnum.getCode(), customMessage, null);
    }
}