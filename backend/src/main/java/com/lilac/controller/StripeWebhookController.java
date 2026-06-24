package com.lilac.controller;

import com.lilac.service.StripePaymentService;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Stripe Webhook 控制器
 */
@RestController
@RequestMapping("/webhook/stripe")
@Slf4j
public class StripeWebhookController {

    @Resource
    private StripePaymentService stripePaymentService;

    /**
     * Stripe Webhook 回调
     * Stripe会在支付状态变更时调用这个接口
     *
     * @param payload 请求体
     * @param sigHeader 请求头
     * @return 处理结果
     */
    @PostMapping
    public String handleStripeWebhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) {
        log.info("收到 Stripe Webhook 回调");
        try {
            // 验证 Webhook 签名
            Event event = stripePaymentService.constructWebhookEvent(payload, sigHeader);
            // 处理不同的事件类型
            switch (event.getType()) {
                case "checkout.session.completed":
                    // 支付成功
                    Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
                    if (session != null) {
                        log.info("支付成功：SessionID {}", session.getId());
                        stripePaymentService.handlePaymentSuccess(session.getId());
                    }
                    break;
                case "checkout.session.expired":
                    // 支付会话过期
                    log.info("支付会话过期");
                    break;
                case "charge.refunded":
                    // 退款
                    log.info("订单退款");
                    break;
                default:
                    log.info("未处理的事件类型：{}", event.getType());
            }
            return "success";
        } catch (Exception e) {
            log.error("处理Stripe Webhook失败", e);
            return "error";
        }
    }
}