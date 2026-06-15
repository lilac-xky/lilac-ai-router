package com.lilac.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.anonation.AuthCheck;
import com.lilac.common.DeleteRequest;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.provider.ProviderAddRequest;
import com.lilac.domain.dto.provider.ProviderQueryRequest;
import com.lilac.domain.dto.provider.ProviderUpdateRequest;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.ProviderVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ModelProviderService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型提供者管理 controller（管理员）
 */
@RestController
@RequestMapping("/provider")
@Slf4j
public class ModelProviderController {

    @Resource
    private ModelProviderService modelProviderService;

    /**
     * 分页查询提供者列表
     *
     * @param queryRequest 查询请求
     * @return 提供者分页
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "分页查询提供者列表")
    public Result<Page<ProviderVO>> listProviderVoByPage(@RequestBody ProviderQueryRequest queryRequest) {
        if (queryRequest == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (StrUtil.isNotBlank(queryRequest.getDisplayName())) {
            queryWrapper.like("displayName", queryRequest.getDisplayName());
        }
        if (StrUtil.isNotBlank(queryRequest.getHealthStatus())) {
            queryWrapper.eq("healthStatus", queryRequest.getHealthStatus());
        }
        if (StrUtil.isNotBlank(queryRequest.getStatus())) {
            queryWrapper.eq("status", queryRequest.getStatus());
        }
        queryWrapper.orderBy("priority", false).orderBy("createTime", false);

        Page<ModelProvider> providerPage = modelProviderService.page(
                Page.of(queryRequest.getCurrent(), queryRequest.getPageSize()), queryWrapper);

        Page<ProviderVO> voPage = new Page<>(providerPage.getPageNumber(), providerPage.getPageSize(), providerPage.getTotalRow());
        List<ProviderVO> voList = providerPage.getRecords().stream()
                .map(this::toProviderVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return Result.success(voPage);
    }

    /**
     * 查询所有提供者（用于下拉选择，不分页）
     *
     * @return 提供者列表
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "查询所有提供者")
    public Result<List<ProviderVO>> listProviderVo() {
        QueryWrapper queryWrapper = QueryWrapper.create().orderBy("priority", false);
        List<ProviderVO> voList = modelProviderService.list(queryWrapper).stream()
                .map(this::toProviderVO)
                .collect(Collectors.toList());
        return Result.success(voList);
    }

    /**
     * 添加提供者
     *
     * @param addRequest 添加请求
     * @return 新增提供者 id
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "添加提供者")
    public Result<Long> addProvider(@RequestBody ProviderAddRequest addRequest) {
        if (addRequest == null || StrUtil.isBlank(addRequest.getProviderName())
                || StrUtil.isBlank(addRequest.getDisplayName())) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "提供者标识和显示名称不能为空");
        }
        ModelProvider provider = BeanUtil.copyProperties(addRequest, ModelProvider.class);
        if (StrUtil.isBlank(provider.getStatus())) {
            provider.setStatus("active");
        }
        provider.setHealthStatus("unknown");
        if (provider.getPriority() == null) {
            provider.setPriority(0);
        }
        boolean saved = modelProviderService.save(provider);
        if (!saved) {
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "添加失败");
        }
        return Result.success(provider.getId());
    }

    /**
     * 更新提供者
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "更新提供者")
    public Result<Boolean> updateProvider(@RequestBody ProviderUpdateRequest updateRequest) {
        if (updateRequest == null || updateRequest.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        ModelProvider provider = modelProviderService.getById(updateRequest.getId());
        if (provider == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "提供者不存在");
        }
        provider.setDisplayName(updateRequest.getDisplayName());
        provider.setBaseUrl(updateRequest.getBaseUrl());
        provider.setStatus(updateRequest.getStatus());
        provider.setPriority(updateRequest.getPriority());
        provider.setConfig(updateRequest.getConfig());
        // API 密钥为空时保留原值，不覆盖
        if (StrUtil.isNotBlank(updateRequest.getApiKey())) {
            provider.setApiKey(updateRequest.getApiKey());
        }
        boolean updated = modelProviderService.updateById(provider);
        return Result.success(updated);
    }

    /**
     * 删除提供者
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "删除提供者")
    public Result<Boolean> deleteProvider(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        boolean removed = modelProviderService.removeById(deleteRequest.getId());
        return Result.success(removed);
    }

    /**
     * 实体转 VO，并对 API 密钥脱敏
     */
    private ProviderVO toProviderVO(ModelProvider provider) {
        ProviderVO vo = BeanUtil.copyProperties(provider, ProviderVO.class);
        String apiKey = provider.getApiKey();
        if (StrUtil.isNotBlank(apiKey)) {
            vo.setApiKey(apiKey.length() > 8
                    ? apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4)
                    : "****");
        }
        return vo;
    }
}
