package com.lilac.service;

import com.lilac.domain.entity.User;
import com.lilac.domain.vo.LoginUserVO;
import com.lilac.domain.vo.UserVO;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户服务
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取脱敏的登录用户信息
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户注销
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取脱敏的用户信息
     */
    UserVO getUserVO(User user);

    /**
     * 是否为管理员
     */
    boolean isAdmin(User user);

    /**
     * 禁用用户
     *
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean disableUser(Long userId);

    /**
     * 启用用户
     *
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean enableUser(Long userId);

    /**
     * 判断用户是否被禁用
     *
     * @param userId 用户ID
     * @return 是否被禁用
     */
    boolean isUserDisabled(Long userId);

    /**
     * 加密密码
     *
     * @param userPassword 用户明文密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String userPassword);
}