package com.lilac.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    /**
     * 用户状态：active-正常，disabled-禁用
     */
    private String userStatus;

    /**
     * Token配额（-1表示无限制）
     */
    private Long tokenQuota;

    /**
     * 已使用Token数
     */
    private Long usedTokens;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
