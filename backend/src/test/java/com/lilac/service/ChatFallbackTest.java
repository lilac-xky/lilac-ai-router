package com.lilac.service;

import com.lilac.adapter.StreamChunk;
import com.lilac.domain.dto.chat.ChatMessage;
import com.lilac.domain.dto.chat.ChatRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.exception.BusinessException;
import com.lilac.metrics.AIMetricsCollector;
import com.lilac.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务 J 回归：主选不可用 / 主选失败时，备选必须真的被尝试。
 *
 * <h3>为什么要专门锁这个行为</h3>
 * 改动前的现象是：指定一个被判 unhealthy 的模型，请求直接返回 {@code 400007 没有可用的模型}，
 * <b>一个备选都没试</b>。根因是主选与备选共用同一个 {@code healthStatus} 过滤条件 ——
 * 主选被过滤掉时备选在结构上必然一起消失。这不是「恰好为空」，是「必然为空」。
 *
 * <p>所以只断言策略的返回值还不够（那是 {@link RoutingStrategyTest} 的事），还得锁住
 * {@code ChatServiceImpl} <b>不会在主选为 null 时直接抛错</b>：否则同样的静默失效
 * 换一个地方就会长回来，而客户端看到的仍然是「一个备选都没试」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatFallbackTest {

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

    private Model primary;
    private Model fallback;

    @BeforeEach
    void setUp() {
        primary = Model.builder().id(1L).providerId(2L).modelKey("primary-model").build();
        fallback = Model.builder().id(2L).providerId(2L).modelKey("fallback-model").build();

        when(userService.isUserDisabled(USER_ID)).thenReturn(false);
        when(modelProviderService.getById(any())).thenReturn(
                ModelProvider.builder().id(2L).providerName("test-provider").build());
        when(userProviderKeyService.getUserProviderApiKey(anyLong(), anyLong())).thenReturn("");
        when(quotaService.checkQuota(USER_ID)).thenReturn(true);
        when(balanceService.getUserBalance(USER_ID)).thenReturn(new BigDecimal("100.0000"));
        when(billingService.calculateCost(any(Model.class), anyInt(), anyInt())).thenReturn(new BigDecimal("0.0100"));
    }

    @Test
    @DisplayName("主选不可用但存在备选：走备选，绝不抛 400007")
    void primaryUnavailable_withFallback_invokesFallback() {
        // 主选返回 null（指定模型不存在 / 已停用），备选还在 —— 这正是改动前直接 400007 的场景
        when(routingService.selectModel(anyString(), anyString(), any())).thenReturn(null);
        when(routingService.getFallbackModels(anyString(), anyString(), any())).thenReturn(List.of(fallback));
        when(modelInvokeService.invoke(any(), any(), any())).thenReturn(aiResponse());

        chatService.chat(request(), USER_ID, null);

        // 关键断言：备选模型真的被调用过。改动前这里一次调用都没有
        verify(modelInvokeService).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("主选与备选都没有：才抛 400007")
    void neitherPrimaryNorFallback_throwsNoAvailableModel() {
        when(routingService.selectModel(anyString(), anyString(), any())).thenReturn(null);
        when(routingService.getFallbackModels(anyString(), anyString(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> chatService.chat(request(), USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可用的模型");

        verify(modelInvokeService, never()).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("主选存在但调用失败：仍然降级到备选")
    void primaryFails_fallsBackToSecondary() {
        when(routingService.selectModel(anyString(), anyString(), any())).thenReturn(primary);
        when(routingService.getFallbackModels(anyString(), anyString(), any())).thenReturn(List.of(fallback));
        // 主选抛错，备选成功
        when(modelInvokeService.invoke(any(), any(), any()))
                .thenThrow(new RuntimeException("上游连接断了"))
                .thenReturn(aiResponse());

        chatService.chat(request(), USER_ID, null);

        verify(modelInvokeService, times(2)).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("流式 · 主选不可用时用首个备选当主模型，不抛错")
    void stream_primaryUnavailable_usesFallbackAsPrimary() {
        when(routingService.selectModel(anyString(), anyString(), any())).thenReturn(null);
        when(routingService.getFallbackModels(anyString(), anyString(), any())).thenReturn(List.of(fallback));
        when(modelInvokeService.invokeStreamChunk(any(), any(), any()))
                .thenReturn(Flux.just(StreamChunk.ofText("ok")));

        List<String> models = chatService.chatStream(request(), USER_ID, null)
                .map(r -> r.getModel())
                .collectList()
                .block();

        assertThat(models).as("流式请求应落在备选模型上，而不是直接 400007").contains("fallback-model");
        verify(modelInvokeService).invokeStreamChunk(any(), any(), any());
    }

    /** 真造 Spring AI 的响应对象太重，这里只要一个「能走完成功路径」的壳，断言都不看它 */
    private ChatResponse aiResponse() {
        return mock(ChatResponse.class, RETURNS_DEEP_STUBS);
    }

    private ChatRequest request() {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent("你好");

        ChatRequest request = new ChatRequest();
        request.setModel("primary-model");
        request.setMessages(List.of(message));
        return request;
    }
}
