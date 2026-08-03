package com.lilac.controller;

import com.lilac.annotation.AuthCheck;
import com.lilac.constant.UserConstant;
import com.lilac.domain.entity.BillingRecord;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.BalanceVO;
import com.lilac.service.BalanceService;
import com.lilac.service.BillingRecordService;
import com.lilac.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 余额管理
 */
@RestController
@RequestMapping("/balance")
@Slf4j
public class BalanceController {

    @Resource
    private BalanceService balanceService;
    @Resource
    private BillingRecordService billingRecordService;
    @Resource
    private UserService userService;

    /**
     * 获取我的余额信息
     *
     * @param request 请求
     * @return 我的余额信息
     */
    @GetMapping("/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<BalanceVO> getMyBalance(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        BigDecimal balance = balanceService.getUserBalance(loginUser.getId());
        BigDecimal totalSpending = billingRecordService.getUserTotalSpending(loginUser.getId());
        BigDecimal totalRecharge = billingRecordService.getUserTotalRecharge(loginUser.getId());

        BalanceVO balanceVO = new BalanceVO();
        balanceVO.setBalance(balance);
        balanceVO.setTotalSpending(totalSpending);
        balanceVO.setTotalRecharge(totalRecharge);
        return Result.success(balanceVO);
    }

    /**
     * 获取我的消费账单
     *
     * @param pageNum  页码
     * @param pageSize 页大小
     * @param request  请求
     * @return 我的消费账单
     */
    @GetMapping("/billing/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Page<BillingRecord>> getMyBillingRecords(@RequestParam(defaultValue = "1") int pageNum,
                                                           @RequestParam(defaultValue = "10") int pageSize, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Page<BillingRecord> page = billingRecordService.listUserBillingRecords(loginUser.getId(), pageNum, pageSize);
        return Result.success(page);
    }
}