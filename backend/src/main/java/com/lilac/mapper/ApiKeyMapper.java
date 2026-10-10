package com.lilac.mapper;

import com.lilac.domain.entity.ApiKey;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * ApiKey Mapper
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    /**
     * 原子性增加ApiKey的调用次数
     */
    @Update("UPDATE api_key SET totalTokens = COALESCE(totalTokens, 0) + #{tokens}, "
            + "lastUsedTime = NOW() WHERE id = #{apiKeyId} AND isDelete = 0")
    int increaseTokensAtomically(@Param("apiKeyId") Long apiKeyId, @Param("tokens") int tokens);
}
