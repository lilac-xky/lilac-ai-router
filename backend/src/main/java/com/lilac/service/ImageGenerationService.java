package com.lilac.service;

import com.lilac.domain.dto.image.ImageGenerationRequest;
import com.lilac.domain.dto.image.ImageGenerationResponse;
import com.lilac.domain.entity.ImageGenerationRecord;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

/**
 * 图片生成服务
 */
public interface ImageGenerationService extends IService<ImageGenerationRecord> {

    /**
     * 生成图片
     *
     * @param request   生成请求
     * @param userId    用户ID
     * @param apiKeyId  API Key ID
     * @param clientIp  客户端IP
     * @return 生成响应
     */
    ImageGenerationResponse generateImage(ImageGenerationRequest request, Long userId, Long apiKeyId, String clientIp);

    /**
     * 获取用户的图片生成记录
     *
     * @param userId   用户ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    Page<ImageGenerationRecord> listUserRecords(Long userId, int pageNum, int pageSize);
}