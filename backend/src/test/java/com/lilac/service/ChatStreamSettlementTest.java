package com.lilac.service;

import com.lilac.adapter.StreamChunk;
import com.lilac.domain.dto.billing.CallReservation;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式「部分交付」结算回归：客户端取消 / 中途中断时，已产出的部分要照收，只有一个字都没产出才全额退回。
 *
 * <p>纯 Mockito 单测，不起 Spring 上下文、不连库。断言的是行为（该 settle 还是该 refund），
 * 不锁死预估 token 的具体数值，换估算口径不用改测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatStreamSettlementTest {

    private static final Long USER_ID = 7L;

    @Mock
    private RoutingService routingService;
    @Mock
    private ModelInvokeService modelInvokeService;
    @Mock
    private ModelProviderService modelProviderService;
    @Mock
    private RequestLogService requestLogService;
    @Mock
    private UserService userService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private BillingService billingService;
    @Mock
    private BalanceService balanceService;
    @Mock
    private CallSettlementService callSettlementService;
    @Mock
    private UserProviderKeyService userProviderKeyService;
    @Mock
    private AIMetricsCollector aiMetricsCollector;

    @InjectMocks
    private ChatServiceImpl chatService;

    private Model model;

    @BeforeEach
    void setUp() {
        model = Model.builder().id(1L).providerId(2L).modelKey("test-model").build();

        when(userService.isUserDisabled(USER_ID)).thenReturn(false);
        when(routingService.selectModel(anyString(), anyString(), any())).thenReturn(model);
        when(routingService.getFallbackModels(anyString(), anyString(), any())).thenReturn(List.of());
        when(modelProviderService.getById(any())).thenReturn(ModelProvider.builder().id(2L).providerName("test-provider").build());
        when(userProviderKeyService.getUserProviderApiKey(anyLong(), anyLong())).thenReturn("");
        when(quotaService.checkQuota(USER_ID)).thenReturn(true);
        when(balanceService.getUserBalance(USER_ID)).thenReturn(new BigDecimal("100.0000"));
        when(billingService.calculateCost(any(Model.class), anyInt(), anyInt())).thenReturn(new BigDecimal("0.0100"));
    }

    @Test
    @DisplayName("客户端取消：已产出的内容照收，不再全额退回")
    void cancelAfterProducingContent_settlesDeliveredPart() {
        when(modelInvokeService.invokeStreamChunk(any(), any(), any()))
                .thenReturn(Flux.just(StreamChunk.ofText("一二三四五六七八")).concatWith(Flux.never()));

        Disposable disposable = chatService.chatStream(request(), USER_ID, null).subscribe();
        disposable.dispose();

        ArgumentCaptor<CallReservation> reservation = ArgumentCaptor.forClass(CallReservation.class);
        verify(callSettlementService).settle(reservation.capture(), intThat(actual -> actual > 0), any(BigDecimal.class));
        verify(callSettlementService, never()).refund(any());

        // 预留是按 max 输出估的，取消时只该结算已交付的那部分，差额由 settle 内部的「多退少补」退掉
        assertThat(reservation.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("客户端取消且一个字都没产出：全额退回预留")
    void cancelWithoutAnyContent_refundsFullReservation() {
        when(modelInvokeService.invokeStreamChunk(any(), any(), any())).thenReturn(Flux.never());

        chatService.chatStream(request(), USER_ID, null).subscribe().dispose();

        verify(callSettlementService).refund(any(CallReservation.class));
        verify(callSettlementService, never()).settle(any(), anyInt(), any());
    }

    @Test
    @DisplayName("流中途出错但已产出内容：按已产出结算，不整份退回")
    void errorAfterProducingContent_settlesDeliveredPart() {
        when(modelInvokeService.invokeStreamChunk(any(), any(), any()))
                .thenReturn(Flux.concat(Flux.just(StreamChunk.ofText("一二三四五六七八")),
                        Flux.error(new RuntimeException("上游连接断了"))));

        chatService.chatStream(request(), USER_ID, null).subscribe(response -> {
        }, error -> {
        });

        verify(callSettlementService).settle(any(CallReservation.class), intThat(actual -> actual > 0), any(BigDecimal.class));
        verify(callSettlementService, never()).refund(any());
    }

    @Test
    @DisplayName("流一开始就失败：全额退回预留")
    void errorBeforeAnyContent_refundsFullReservation() {
        when(modelInvokeService.invokeStreamChunk(any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("上游直接拒绝")));

        chatService.chatStream(request(), USER_ID, null).subscribe(response -> {
        }, error -> {
        });

        verify(callSettlementService).refund(any(CallReservation.class));
        verify(callSettlementService, never()).settle(any(), anyInt(), any());
    }

    @Test
    @DisplayName("流正常跑完：按真实 usage 结算")
    void normalCompletion_settlesWithRealUsage() {
        StreamChunk last = StreamChunk.builder()
                .text("完")
                .promptTokens(30)
                .completionTokens(20)
                .build();
        when(modelInvokeService.invokeStreamChunk(any(), any(), any()))
                .thenReturn(Flux.just(StreamChunk.ofText("一二三四五六七八"), last));

        chatService.chatStream(request(), USER_ID, null).subscribe();

        verify(callSettlementService).settle(any(CallReservation.class), intThat(actual -> actual == 50), any(BigDecimal.class));
        verify(callSettlementService, never()).refund(any());
    }

    private ChatRequest request() {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent("你好");

        ChatRequest request = new ChatRequest();
        request.setModel("test-model");
        request.setMessages(List.of(message));
        return request;
    }
}
