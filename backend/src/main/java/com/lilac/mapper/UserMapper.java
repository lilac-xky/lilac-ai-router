package com.lilac.mapper;

import com.lilac.domain.entity.User;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子扣除用户 token使用量
     *
     * @param userId 用户ID
     * @param tokens 令牌数
     * @return 影响行数
     */
    @Update("UPDATE `user` SET usedTokens = COALESCE(usedTokens, 0) + #{tokens} "
            + "WHERE id = #{userId} AND isDelete = 0 "
            + "AND (tokenQuota IS NULL OR tokenQuota = -1 "
            + "     OR COALESCE(usedTokens, 0) + #{tokens} <= tokenQuota)")
    int deductTokensAtomically(@Param("userId") Long userId, @Param("tokens") int tokens);

    /**
     * 原子扣减余额，余额不足时不更新任何行
     *
     * @param userId 用户ID
     * @param amount 扣减金额
     * @return 影响行数：0 = 余额不足 / 用户不存在 / 已删除
     */
    @Update("UPDATE `user` SET balance = COALESCE(balance, 0) - #{amount} "
            + "WHERE id = #{userId} AND isDelete = 0 "
            + "AND COALESCE(balance, 0) >= #{amount}")
    int deductBalanceAtomically(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 原子增加余额
     *
     * @param userId 用户ID
     * @param amount 增加金额
     * @return 影响行数
     */
    @Update("UPDATE `user` SET balance = COALESCE(balance, 0) + #{amount} "
            + "WHERE id = #{userId} AND isDelete = 0")
    int addBalanceAtomically(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
