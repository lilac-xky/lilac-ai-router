package com.lilac.constant;

/**
 * 用户常量
 */
public interface UserConstant {

    /**
     * 用户登录态键
     */
    String USER_LOGIN_STATE = "user_login";

    /**
     * 默认角色（普通用户）
     */
    String USER = "user";

    /**
     * 管理员角色
     */
    String ADMIN = "admin";

    /**
     * 默认权限
     */
    String DEFAULT_ROLE = "user";

    /**
     * 默认密码
     */
    String DEFAULT_PASSWORD = "12345678";

    /**
     * 混淆盐值，混淆密码
     */
    String SALT = "lilac";
}
