package com.lilac.domain.dto.billing;

import java.math.BigDecimal;

/**
 * 模型调用的预留凭据。
 */
public record CallReservation(Long userId, String modelKey, int tokens, BigDecimal cost, String channel) {

    /**
     * 空凭据：匿名 / BYOK 调用用，所有结算动作对它都是空操作。
     */
    public static CallReservation none() {
        return new CallReservation(null, null, 0, BigDecimal.ZERO, null);
    }

    /**
     * 是否真的占用了平台资源。
     */
    public boolean isActive() {
        return userId != null && (tokens > 0 || (cost != null && cost.compareTo(BigDecimal.ZERO) > 0));
    }

    /**
     * 账单描述，如「网页调用消费（预留）」。
     */
    public String description(String suffix) {
        String prefix = channel != null ? channel : "模型调用消费";
        return suffix == null || suffix.isBlank() ? prefix : prefix + "（" + suffix + "）";
    }
}
