package com.lilac.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.lilac.anonation.AuthCheck;
import com.lilac.common.DeleteRequest;
import com.lilac.constant.UserConstant;
import com.lilac.domain.dto.model.ModelAddRequest;
import com.lilac.domain.dto.model.ModelQueryRequest;
import com.lilac.domain.dto.model.ModelUpdateRequest;
import com.lilac.domain.entity.Model;
import com.lilac.domain.entity.ModelProvider;
import com.lilac.domain.result.Result;
import com.lilac.domain.vo.ModelVO;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.service.ModelProviderService;
import com.lilac.service.ModelService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 模型管理 controller（管理员）
 */
@RestController
@RequestMapping("/model")
@Slf4j
public class ModelController {

    @Resource
    private ModelService modelService;
    @Resource
    private ModelProviderService modelProviderService;

    /**
     * 分页查询模型列表
     *
     * @param queryRequest 查询请求
     * @return 模型分页
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "分页查询模型列表")
    public Result<Page<ModelVO>> listModelVoByPage(@RequestBody ModelQueryRequest queryRequest) {
        if (queryRequest == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        QueryWrapper queryWrapper = QueryWrapper.create();
        if (StrUtil.isNotBlank(queryRequest.getModelName())) {
            queryWrapper.like("modelName", queryRequest.getModelName());
        }
        if (StrUtil.isNotBlank(queryRequest.getModelType())) {
            queryWrapper.eq("modelType", queryRequest.getModelType());
        }
        if (StrUtil.isNotBlank(queryRequest.getStatus())) {
            queryWrapper.eq("status", queryRequest.getStatus());
        }
        if (queryRequest.getProviderId() != null) {
            queryWrapper.eq("providerId", queryRequest.getProviderId());
        }
        queryWrapper.orderBy("priority", false).orderBy("createTime", false);

        Page<Model> modelPage = modelService.page(
                Page.of(queryRequest.getCurrent(), queryRequest.getPageSize()), queryWrapper);

        // 批量查询提供者显示名称，避免 N+1
        Map<Long, String> providerNameMap = loadProviderNameMap(modelPage.getRecords());

        Page<ModelVO> voPage = new Page<>(modelPage.getPageNumber(), modelPage.getPageSize(), modelPage.getTotalRow());
        List<ModelVO> voList = modelPage.getRecords().stream()
                .map(model -> {
                    ModelVO vo = BeanUtil.copyProperties(model, ModelVO.class);
                    vo.setProviderDisplayName(providerNameMap.get(model.getProviderId()));
                    return vo;
                })
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return Result.success(voPage);
    }

    /**
     * 添加模型
     *
     * @param addRequest 添加请求
     * @return 新增模型 id
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "添加模型")
    public Result<Long> addModel(@RequestBody ModelAddRequest addRequest) {
        if (addRequest == null || addRequest.getProviderId() == null
                || StrUtil.isBlank(addRequest.getModelKey()) || StrUtil.isBlank(addRequest.getModelName())) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "提供者、模型标识和模型名称不能为空");
        }
        ModelProvider provider = modelProviderService.getById(addRequest.getProviderId());
        if (provider == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR, "所选提供者不存在");
        }
        Model model = BeanUtil.copyProperties(addRequest, Model.class);
        model.setStatus("active");
        model.setHealthStatus("unknown");
        if (model.getModelType() == null) {
            model.setModelType("chat");
        }
        if (model.getPriority() == null) {
            model.setPriority(0);
        }
        if (model.getSupportReasoning() == null) {
            model.setSupportReasoning(0);
        }
        boolean saved = modelService.save(model);
        if (!saved) {
            throw new BusinessException(HttpsCodeEnum.OPERATION_ERROR, "添加失败");
        }
        return Result.success(model.getId());
    }

    /**
     * 更新模型
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "更新模型")
    public Result<Boolean> updateModel(@RequestBody ModelUpdateRequest updateRequest) {
        if (updateRequest == null || updateRequest.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        Model model = modelService.getById(updateRequest.getId());
        if (model == null) {
            throw new BusinessException(HttpsCodeEnum.NOT_FOUND_ERROR, "模型不存在");
        }
        model.setModelName(updateRequest.getModelName());
        model.setDescription(updateRequest.getDescription());
        model.setContextLength(updateRequest.getContextLength());
        model.setInputPrice(updateRequest.getInputPrice());
        model.setOutputPrice(updateRequest.getOutputPrice());
        model.setStatus(updateRequest.getStatus());
        model.setPriority(updateRequest.getPriority());
        model.setDefaultTimeout(updateRequest.getDefaultTimeout());
        model.setSupportReasoning(updateRequest.getSupportReasoning());
        model.setCapabilities(updateRequest.getCapabilities());
        boolean updated = modelService.updateById(model);
        return Result.success(updated);
    }

    /**
     * 删除模型
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN)
    @Operation(summary = "删除模型")
    public Result<Boolean> deleteModel(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() == null) {
            throw new BusinessException(HttpsCodeEnum.PARAMS_ERROR);
        }
        boolean removed = modelService.removeById(deleteRequest.getId());
        return Result.success(removed);
    }

    /**
     * 根据模型列表批量加载提供者 id -> displayName 映射
     */
    private Map<Long, String> loadProviderNameMap(List<Model> models) {
        List<Long> providerIds = models.stream()
                .map(Model::getProviderId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(providerIds)) {
            return Map.of();
        }
        return modelProviderService.listByIds(providerIds).stream()
                .collect(Collectors.toMap(ModelProvider::getId, ModelProvider::getDisplayName, (a, b) -> a));
    }
}
