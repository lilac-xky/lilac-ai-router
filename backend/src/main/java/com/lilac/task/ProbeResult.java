package com.lilac.task;

import com.lilac.enums.ProbeFailureKind;

/**
 * 一次健康探测的结果。
 */
public record ProbeResult(ProbeFailureKind failureKind, int ttftMillis, int totalMillis) {

    public static ProbeResult success(int ttftMillis, int totalMillis) {
        return new ProbeResult(null, ttftMillis, totalMillis);
    }

    public static ProbeResult failure(ProbeFailureKind kind, int totalMillis) {
        return new ProbeResult(kind, -1, totalMillis);
    }

    public boolean isSuccess() {
        return failureKind == null;
    }
}
