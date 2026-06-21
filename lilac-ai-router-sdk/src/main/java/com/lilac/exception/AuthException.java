package com.lilac.exception;

/**
 * 认证异常
 */
public class AuthException extends LilacAIException{

    public AuthException(String message) {
        super(401, message);
    }

    public AuthException(String message, Throwable cause) {
        super(401, message, cause);
    }
}
