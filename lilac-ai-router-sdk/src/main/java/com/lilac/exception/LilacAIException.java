package com.lilac.exception;

/**
 * LilacAI 异常类
 */
public class LilacAIException extends RuntimeException {

    private final int code;

    public LilacAIException(String message) {
        super(message);
        this.code = -1;
    }

    public LilacAIException(int code, String message) {
        super(message);
        this.code = code;
    }

    public LilacAIException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public LilacAIException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
