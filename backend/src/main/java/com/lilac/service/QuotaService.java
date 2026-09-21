package com.lilac.service;

/**
 * 配额服务接口
 */
public interface QuotaService {
    /**
     * 检查用户是否有足够的配额
     *
     * @param userId 用户ID；为 null（匿名 / BYOK）返回 true
     */
    boolean checkQuota(Long userId);

    /**
     * 原子扣减 Token。
     *
     * @return true = 放行（含 userId 为 null / tokens &lt;= 0 的「不该扣」）；false = 没扣到
     */
    boolean deductTokens(Long userId, int tokens);

    /**
     * 预留：调用下游前按预估值原子占用配额。
     *
     * @return true = 占住了，可以发起调用；false = 额度不够
     */
    boolean reserveTokens(Long userId, int estimatedTokens);

    /**
     * 结算：实际高于预留则补扣，低于则退回。
     */
    void settleTokens(Long userId, int reservedTokens, int actualTokens);

    /**
     * 退回预留（调用失败 / 取消时用）。不会退成负数；「只退一次」由调用方保证。
     */
    void refundTokens(Long userId, int tokens);

    /**
     * 剩余配额；-1 表示无限
     */
    long getRemainingQuota(Long userId);
}
