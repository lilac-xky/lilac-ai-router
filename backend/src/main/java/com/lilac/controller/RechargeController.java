package com.lilac.controller;

import com.lilac.anonation.AuthCheck;
import com.lilac.config.StripeConfig;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.recharge.CreateRechargeRequest;
import com.lilac.domain.entity.RechargeRecord;
import com.lilac.domain.entity.User;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.RechargeVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.RechargeService;
import com.lilac.service.StripePaymentService;
import com.lilac.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.stripe.model.checkout.Session;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 充值控制器
 */
@RestController
@RequestMapping("/recharge")
@Slf4j
public class RechargeController {

    @Resource
    private RechargeService rechargeService;
    @Resource
    private StripePaymentService stripePaymentService;
    @Resource
    private UserService userService;
    @Resource
    private StripeConfig stripeConfig;

    /**
     * 创建Stripe充值订单
     *
     * @param request 请求
     * @return 创建的充值订单信息
     */
    @PostMapping("/stripe/create")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<RechargeVO> createStripeRecharge(@RequestBody CreateRechargeRequest request,
                                                   HttpServletRequest httpRequest) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "充值金额必须大于0");
        }
        // 设置最小充值金额10元，最大10000元
        if (request.getAmount().compareTo(new BigDecimal("10")) < 0 || request.getAmount().compareTo(new BigDecimal("10000")) > 0) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "充值金额必须在10-10000元之间");
        }
        User loginUser = userService.getLoginUser(httpRequest);
        // 从配置获取回调URL
        String successUrl = stripeConfig.getSuccessUrl();
        String cancelUrl = stripeConfig.getCancelUrl();
        // 创建 Stripe 支付会话
        Session session = stripePaymentService.createCheckoutSession(loginUser.getId(), request.getAmount(), successUrl, cancelUrl);
        RechargeVO response = new RechargeVO();
        response.setCheckoutUrl(session.getUrl());
        response.setSessionId(session.getId());
        return Result.success(response);
    }

    /**
     * Stripe充值成功回调
     *
     * @param sessionId Stripe会话ID
     * @return 是否处理成功
     */
    @GetMapping("/stripe/success")
    public Result<String> stripeSuccess(@RequestParam("session_id") String sessionId) {
        log.info("收到Stripe支付成功回调，SessionID：{}", sessionId);
        boolean success = stripePaymentService.handlePaymentSuccess(sessionId);
        if (success) {
            return Result.success("充值成功！");
        } else {
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "充值处理失败");
        }
    }

    /**
     * Stripe充值取消回调
     *
     * @return 是否处理成功
     */
    @GetMapping("/stripe/cancel")
    public Result<String> stripeCancel() {
        log.info("用户取消了Stripe支付");
        return Result.success("您取消了充值");
    }

    /**
     * 获取我的充值记录
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @param request  请求
     * @return 充值记录列表
     */
    @GetMapping("/list/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public Result<Page<RechargeRecord>> getMyRechargeRecords(@RequestParam(defaultValue = "1") int pageNum,
                                                             @RequestParam(defaultValue = "10") int pageSize, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Page<RechargeRecord> page = rechargeService.listUserRechargeRecords(loginUser.getId(), pageNum, pageSize);
        return Result.success(page);
    }
}