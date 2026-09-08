package com.lilac.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.annotation.AuthCheck;
import com.lilac.common.DeleteRequest;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.user.*;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.LoginUserVO;
import com.lilac.domain.vo.UserAnalysisVO;
import com.lilac.domain.vo.UserVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.service.BillingService;
import com.lilac.service.QuotaService;
import com.lilac.service.RequestLogService;
import com.lilac.service.UserService;
import com.lilac.utils.ThrowUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.lilac.constant.UserConstant.DEFAULT_PASSWORD;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Resource
    private UserService userService;
    @Resource
    private QuotaService quotaService;
    @Resource
    private RequestLogService requestLogService;
    @Resource
    private BillingService billingService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return Result.success(result);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return Result.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     */
    @GetMapping("/get/login")
    public Result<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        return Result.success(userService.getLoginUserVO(user));
    }

    /**
     * 用户注销
     */
    @PostMapping("/logout")
    public Result<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, HttpsCodeEnum.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return Result.success(result);
    }

    /**
     * 分页查询用户列表（仅管理员）
     *
     * @param queryRequest 查询请求
     * @return 用户分页
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Page<UserVO>> listUserVoByPage(@RequestBody UserQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (queryRequest.getId() != null) {
            queryWrapper.eq("id", queryRequest.getId());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserAccount())) {
            queryWrapper.like("userAccount", queryRequest.getUserAccount());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserName())) {
            queryWrapper.like("userName", queryRequest.getUserName());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserProfile())) {
            queryWrapper.like("userProfile", queryRequest.getUserProfile());
        }
        queryWrapper.orderBy("createTime", false);

        Page<User> userPage = userService.page(
                Page.of(queryRequest.getCurrent(), queryRequest.getPageSize()), queryWrapper);

        Page<UserVO> voPage = new Page<>(userPage.getPageNumber(), userPage.getPageSize(), userPage.getTotalRow());
        List<UserVO> voList = userPage.getRecords().stream()
                .map(userService::getUserVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return Result.success(voPage);
    }

    /**
     * 添加用户（仅管理员）
     *
     * @param addRequest 添加请求
     * @return 新增用户 id
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Long> addUser(@RequestBody UserAddRequest addRequest) {
        ThrowUtils.throwIf(addRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(addRequest.getUserAccount()), HttpsCodeEnum.PARAMS_ERROR, "账号不能为空");
        // 校验账号是否重复
        long count = userService.count(QueryWrapper.create().eq("userAccount", addRequest.getUserAccount()));
        ThrowUtils.throwIf(count > 0, HttpsCodeEnum.PARAMS_ERROR, "账号已存在");
        User user = BeanUtil.copyProperties(addRequest, User.class);
        user.setUserPassword(userService.getEncryptPassword(DEFAULT_PASSWORD));
        if (StrUtil.isBlank(user.getUserName())) {
            user.setUserName(addRequest.getUserAccount());
        }
        user.setUserRole(UserConstant.USER);
        user.setUserStatus("active");
        boolean saved = userService.save(user);
        ThrowUtils.throwIf(!saved, HttpsCodeEnum.OPERATION_ERROR, "添加失败");
        return Result.success(user.getId());
    }

    /**
     * 更新用户（仅管理员）
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> updateUser(@RequestBody UserUpdateRequest updateRequest) {
        ThrowUtils.throwIf(updateRequest == null || updateRequest.getId() == null, HttpsCodeEnum.PARAMS_ERROR);
        User user = userService.getById(updateRequest.getId());
        ThrowUtils.throwIf(user == null, HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        user.setUserName(updateRequest.getUserName());
        user.setUserAvatar(updateRequest.getUserAvatar());
        user.setUserProfile(updateRequest.getUserProfile());
        if (StrUtil.isNotBlank(updateRequest.getUserRole())) {
            user.setUserRole(updateRequest.getUserRole());
        }
        boolean updated = userService.updateById(user);
        return Result.success(updated);
    }

    /**
     * 删除用户（仅管理员）
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, HttpsCodeEnum.PARAMS_ERROR);
        boolean removed = userService.removeById(deleteRequest.getId());
        return Result.success(removed);
    }

    /**
     * 设置用户配额
     *
     * @param quotaUpdateRequest 用户配额更新请求
     * @return 响应
     */
    @PostMapping("/quota/set")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> setUserQuota(@RequestBody QuotaUpdateRequest quotaUpdateRequest) {
        ThrowUtils.throwIf(quotaUpdateRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        ThrowUtils.throwIf(quotaUpdateRequest.getUserId() == null, HttpsCodeEnum.PARAMS_ERROR, "用户ID不能为空");
        ThrowUtils.throwIf(quotaUpdateRequest.getTokenQuota() == null, HttpsCodeEnum.PARAMS_ERROR, "配额不能为空");
        User user = new User();
        user.setId(quotaUpdateRequest.getUserId());
        user.setTokenQuota(quotaUpdateRequest.getTokenQuota());
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, HttpsCodeEnum.OPERATION_ERROR);
        return Result.success(true);
    }

    /**
     * 重置用户已使用配额
     *
     * @param userId 用户ID
     * @return 响应
     */
    @PostMapping("/quota/reset")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> resetUserQuota(@RequestParam Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, HttpsCodeEnum.PARAMS_ERROR, "用户ID不合法");
        User user = new User();
        user.setId(userId);
        user.setUsedTokens(0L);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, HttpsCodeEnum.OPERATION_ERROR);
        return Result.success(true);
    }

    /**
     * 禁用用户（仅管理员）
     *
     * @param userId 用户ID
     * @return 响应
     */
    @PostMapping("/disable")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> disableUser(@RequestParam Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, HttpsCodeEnum.PARAMS_ERROR, "用户ID不合法");
        // 不能禁用自己
        // 可以在这里添加更多校验
        boolean result = userService.disableUser(userId);
        ThrowUtils.throwIf(!result, HttpsCodeEnum.OPERATION_ERROR);
        return Result.success(true);
    }

    /**
     * 启用用户（仅管理员）
     *
     * @param userId 用户ID
     * @return 响应
     */
    @PostMapping("/enable")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Boolean> enableUser(@RequestParam Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, HttpsCodeEnum.PARAMS_ERROR, "用户ID不合法");

        boolean result = userService.enableUser(userId);
        ThrowUtils.throwIf(!result, HttpsCodeEnum.OPERATION_ERROR);
        return Result.success(true);
    }

    /**
     * 获取用户使用分析数据（仅管理员）
     *
     * @param userId 用户ID
     * @return 响应
     */
    @GetMapping("/analysis")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<UserAnalysisVO> getUserAnalysis(@RequestParam Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, HttpsCodeEnum.PARAMS_ERROR, "用户ID不合法");
        User user = userService.getById(userId);
        ThrowUtils.throwIf(user == null, HttpsCodeEnum.NOT_FOUND_ERROR, "用户不存在");
        UserAnalysisVO analysisVO = new UserAnalysisVO();
        analysisVO.setUserId(userId);
        analysisVO.setUserAccount(user.getUserAccount());
        analysisVO.setUserName(user.getUserName());
        analysisVO.setUserStatus(user.getUserStatus());
        analysisVO.setUserRole(user.getUserRole());

        // 配额信息
        analysisVO.setTokenQuota(user.getTokenQuota());
        analysisVO.setUsedTokens(user.getUsedTokens() != null ? user.getUsedTokens() : 0L);
        analysisVO.setRemainingQuota(quotaService.getRemainingQuota(userId));

        // 请求统计
        analysisVO.setTotalRequests(requestLogService.countUserRequests(userId));
        analysisVO.setSuccessRequests(requestLogService.countUserSuccessRequests(userId));
        analysisVO.setTotalTokens(requestLogService.countUserTokens(userId));

        // 费用统计
        analysisVO.setTotalCost(billingService.getUserTotalCost(userId));
        analysisVO.setTodayCost(billingService.getUserTodayCost(userId));
        return Result.success(analysisVO);
    }
}