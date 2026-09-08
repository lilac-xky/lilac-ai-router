package com.lilac.controller;

import com.lilac.annotation.AuthCheck;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.log.RequestLogQueryRequest;
import com.lilac.domain.entity.RequestLog;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.CostStatsVO;
import com.lilac.domain.vo.UserSummaryStatsVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.service.BillingService;
import com.lilac.service.QuotaService;
import com.lilac.service.RequestLogService;
import com.lilac.service.UserService;
import com.lilac.utils.ThrowUtils;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计控制器
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    @Resource
    private RequestLogService requestLogService;
    @Resource
    private UserService userService;
    @Resource
    private BillingService billingService;
    @Resource
    private QuotaService quotaService;

    /**
     * 获取我的综合统计数据
     *
     * @param request 请求
     * @return 响应
     */
    @GetMapping("/my/summary")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<UserSummaryStatsVO> getMySummaryStats(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Long userId = loginUser.getId();
        UserSummaryStatsVO vo = new UserSummaryStatsVO();
        // Token 统计
        vo.setTotalTokens(requestLogService.countUserTokens(userId));
        // 配额信息
        vo.setTokenQuota(loginUser.getTokenQuota());
        vo.setUsedTokens(loginUser.getUsedTokens() != null ? loginUser.getUsedTokens() : 0L);
        vo.setRemainingQuota(quotaService.getRemainingQuota(userId));
        // 费用统计
        vo.setTotalCost(billingService.getUserTotalCost(userId));
        vo.setTodayCost(billingService.getUserTodayCost(userId));
        // 请求统计
        vo.setTotalRequests(requestLogService.countUserRequests(userId));
        vo.setSuccessRequests(requestLogService.countUserSuccessRequests(userId));
        return Result.success(vo);
    }

    /**
     * 获取我的每日统计数据
     *
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @param request   请求
     * @return 响应
     */
    @GetMapping("/my/daily")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<List<Map<String, Object>>> getMyDailyStats(@RequestParam(required = false) String startDate,
                                                             @RequestParam(required = false) String endDate, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 默认查询最近7天
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : end.minusDays(6);
        List<Map<String, Object>> dailyStats = requestLogService.getUserDailyStats(loginUser.getId(), start, end);
        return Result.success(dailyStats);
    }

    /**
     * 获取我的费用统计
     *
     * @param request 请求
     * @return 响应
     */
    @GetMapping("/my/cost")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<CostStatsVO> getMyCostStats(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        BigDecimal totalCost = billingService.getUserTotalCost(loginUser.getId());
        BigDecimal todayCost = billingService.getUserTodayCost(loginUser.getId());
        CostStatsVO vo = new CostStatsVO();
        vo.setTotalCost(totalCost);
        vo.setTodayCost(todayCost);
        return Result.success(vo);
    }

    /**
     * 分页查询我的调用历史
     *
     * @param queryRequest 查询请求
     * @param request      请求
     * @return 响应
     */
    @PostMapping("/history/my/page")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Page<RequestLog>> pageMyHistory(@RequestBody RequestLogQueryRequest queryRequest,
                                                        HttpServletRequest request) {
        ThrowUtils.throwIf(queryRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 强制只查询当前用户的数据
        queryRequest.setUserId(loginUser.getId());
        Page<RequestLog> page = requestLogService.pageByQuery(queryRequest);
        return Result.success(page);
    }

    /**
     * 获取调用历史详情
     *
     * @param id       调用历史ID
     * @param request  请求
     * @return 响应
     */
    @GetMapping("/history/detail")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<RequestLog> getHistoryDetail(@RequestParam Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, HttpsCodeEnum.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        RequestLog requestLog = requestLogService.getById(id);
        ThrowUtils.throwIf(requestLog == null, HttpsCodeEnum.NOT_FOUND_ERROR);
        // 校验是否是当前用户的数据（非管理员只能查看自己的）
        if (!UserConstant.ADMIN.equals(loginUser.getUserRole())) {
            ThrowUtils.throwIf(!loginUser.getId().equals(requestLog.getUserId()), HttpsCodeEnum.UNAUTHORIZED, "只能查看自己的调用历史");
        }
        return Result.success(requestLog);
    }

    /**
     * 分页查询所有调用历史（仅管理员）
     *
     * @param queryRequest 查询请求
     * @return 响应
     */
    @PostMapping("/history/page")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    public Result<Page<RequestLog>> pageHistory(@RequestBody RequestLogQueryRequest queryRequest) {
        ThrowUtils.throwIf(queryRequest == null, HttpsCodeEnum.PARAMS_ERROR);
        Page<RequestLog> page = requestLogService.pageByQuery(queryRequest);
        return Result.success(page);
    }
}
