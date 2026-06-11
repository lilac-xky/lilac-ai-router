package com.lilac.adapter;

import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型适配器工厂
 */
@Slf4j
@Component
public class ModelAdapterFactory {

    @Resource
    private List<ModelAdapter> adapters;

    /**
     * 根据提供者名称获取对应的适配器
     *
     * @param providerName 提供者名称
     * @return 模型适配器
     */
    public ModelAdapter getAdapter(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "提供者名称不能为空");
        }
        for (ModelAdapter adapter : adapters) {
            if (adapter.supports(providerName)) {
                log.debug("为提供者 {} 选择适配器: {}", providerName, adapter.getClass().getSimpleName());
                return adapter;
            }
        }
        log.warn("未找到支持提供者 {} 的适配器，将使用 OpenAI 兼容适配器", providerName);
        // 默认使用 OpenAI 兼容适配器（因为很多模型都兼容 OpenAI 格式）
        return adapters.stream()
                .filter(adapter -> adapter instanceof OpenAIAdapter)
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpsCodeEnum.SYSTEM_ERROR, "未找到可用的模型适配器"));
    }
}