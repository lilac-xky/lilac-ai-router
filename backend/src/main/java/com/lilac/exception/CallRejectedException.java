package com.lilac.exception;

import com.lilac.enums.HttpsCodeEnum;

/**
 * 调用被闸门拒绝：账户侧资源不足（配额 / 余额 / 用户不存在），请求<b>没有打到上游模型</b>。
 */
public class CallRejectedException extends BusinessException {

    public CallRejectedException(HttpsCodeEnum httpsCodeEnum, String customMessage) {
        super(httpsCodeEnum, customMessage);
    }
}
