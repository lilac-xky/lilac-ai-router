package com.lilac.exception;

/**
 * 限流异常
 */
public class RateLimitException extends LilacAIException{
    public RateLimitException(String message) {
        super(429, message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(429, message, cause);
    }
}
