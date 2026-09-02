/*
 Navicat Premium Dump SQL

 Source Server Type    : MySQL
 Source Server Version : 80044 (8.0.44)
 Source Host           : localhost:3306
 Source Schema         : lilac-ai-router

 Target Server Type    : MySQL
 Target Server Version : 80044 (8.0.44)
 File Encoding         : 65001
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for api_key
-- ----------------------------
DROP TABLE IF EXISTS `api_key`;
CREATE TABLE `api_key`  (
  `id` bigint NOT NULL COMMENT 'id',
  `userId` bigint NOT NULL COMMENT '用户id',
  `keyValue` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'API Key',
  `keyName` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Key名称',
  `status` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：active/inactive/revoked',
  `totalTokens` int NULL DEFAULT NULL COMMENT '已使用Token总数',
  `lastUsedTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后使用时间',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for billing_record
-- ----------------------------
DROP TABLE IF EXISTS `billing_record`;
CREATE TABLE `billing_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userId` bigint NOT NULL COMMENT '用户id',
  `requestLogId` bigint NULL DEFAULT NULL COMMENT '关联的请求日志ID',
  `amount` decimal(12, 4) NOT NULL COMMENT '消费金额（元）',
  `balanceBefore` decimal(12, 4) NOT NULL COMMENT '消费前余额（元）',
  `balanceAfter` decimal(12, 4) NOT NULL COMMENT '消费后余额（元）',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '消费说明',
  `billingType` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'api_call' COMMENT '账单类型：api_call/recharge/refund',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_billingType`(`billingType` ASC) USING BTREE,
  INDEX `idx_createTime`(`createTime` ASC) USING BTREE,
  INDEX `idx_requestLogId`(`requestLogId` ASC) USING BTREE,
  INDEX `idx_userId`(`userId` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 427328890915651585 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '消费账单' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for image_generation_record
-- ----------------------------
DROP TABLE IF EXISTS `image_generation_record`;
CREATE TABLE `image_generation_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userId` bigint NOT NULL COMMENT '用户id',
  `apiKeyId` bigint NULL DEFAULT NULL COMMENT 'API Key id',
  `modelId` bigint NOT NULL COMMENT '使用的模型id',
  `modelKey` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型标识',
  `prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生成提示词',
  `revisedPrompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '修订后的提示词',
  `imageUrl` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '图片URL',
  `imageData` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'Base64图片数据',
  `size` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '图片尺寸',
  `quality` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '图片质量',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '状态：success/failed',
  `cost` decimal(12, 4) NOT NULL DEFAULT 0.0000 COMMENT '生成费用（元）',
  `duration` int NULL DEFAULT NULL COMMENT '耗时（毫秒）',
  `errorMessage` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '错误信息',
  `clientIp` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '客户端IP',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_userId`(`userId` ASC) USING BTREE,
  INDEX `idx_modelId`(`modelId` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '图片生成记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for model
-- ----------------------------
DROP TABLE IF EXISTS `model`;
CREATE TABLE `model`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `providerId` bigint NOT NULL COMMENT '提供者id',
  `modelKey` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型标识（如：qwen-plus）',
  `modelName` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型显示名称',
  `modelType` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'chat' COMMENT '模型类型：chat/embedding/image/audio',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型描述',
  `contextLength` int NOT NULL DEFAULT 4096 COMMENT '上下文长度限制',
  `inputPrice` decimal(10, 6) NOT NULL DEFAULT 0.000000 COMMENT '输入价格（元/千Token）',
  `outputPrice` decimal(10, 6) NOT NULL DEFAULT 0.000000 COMMENT '输出价格（元/千Token）',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/deprecated',
  `healthStatus` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'unknown' COMMENT '健康状态：healthy/unhealthy/degraded/unknown',
  `avgLatency` int NOT NULL DEFAULT 0 COMMENT '平均延迟（毫秒）',
  `successRate` decimal(5, 2) NOT NULL DEFAULT 100.00 COMMENT '成功率（百分比）',
  `score` decimal(10, 4) NOT NULL DEFAULT 0.0000 COMMENT '综合得分（越低越好）',
  `priority` int NOT NULL DEFAULT 100 COMMENT '优先级（越大越优先）',
  `defaultTimeout` int NOT NULL DEFAULT 60000 COMMENT '默认超时时间（毫秒）',
  `supportReasoning` tinyint NOT NULL DEFAULT 0 COMMENT '是否支持深度思考：0=不支持，1=支持',
  `capabilities` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '能力标签（JSON数组）',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_modelKey`(`modelKey` ASC) USING BTREE,
  INDEX `idx_providerId`(`providerId` ASC) USING BTREE,
  INDEX `idx_modelType`(`modelType` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_healthStatus`(`healthStatus` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 441765099462643713 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for model_provider
-- ----------------------------
DROP TABLE IF EXISTS `model_provider`;
CREATE TABLE `model_provider`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `providerName` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供者名称（如：qwen/zhipu/deepseek）',
  `displayName` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '显示名称（如：通义千问/智谱AI/DeepSeek）',
  `baseUrl` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'API基础URL',
  `apiKey` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'API密钥',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive/maintenance',
  `healthStatus` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'unknown' COMMENT '健康状态：healthy/unhealthy/degraded/unknown',
  `avgLatency` int NOT NULL DEFAULT 0 COMMENT '平均延迟（毫秒）',
  `successRate` decimal(5, 2) NOT NULL DEFAULT 100.00 COMMENT '成功率（百分比）',
  `priority` int NOT NULL DEFAULT 100 COMMENT '优先级（越大越优先）',
  `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '额外配置（JSON格式）',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_providerName`(`providerName` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_healthStatus`(`healthStatus` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型提供者' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for plugin_config
-- ----------------------------
DROP TABLE IF EXISTS `plugin_config`;
CREATE TABLE `plugin_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `pluginKey` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '插件标识：web_search/pdf_parser/image_recognition',
  `pluginName` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '插件名称',
  `pluginType` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'builtin' COMMENT '插件类型：builtin/custom',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '插件描述',
  `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '插件配置（JSON）',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态：active/inactive',
  `priority` int NOT NULL DEFAULT 100 COMMENT '优先级',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_pluginKey`(`pluginKey` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '插件配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for recharge_record
-- ----------------------------
DROP TABLE IF EXISTS `recharge_record`;
CREATE TABLE `recharge_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userId` bigint NOT NULL COMMENT '用户id',
  `amount` decimal(12, 4) NOT NULL COMMENT '充值金额（元）',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT '充值状态：pending/success/failed',
  `paymentMethod` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '支付方式：stripe/alipay/wechat',
  `paymentId` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '支付平台订单ID',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '充值说明',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_userId`(`userId` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_paymentId`(`paymentId` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 427328425142386689 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '充值记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for request_log
-- ----------------------------
DROP TABLE IF EXISTS `request_log`;
CREATE TABLE `request_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userId` bigint NULL DEFAULT NULL COMMENT '用户id',
  `apiKeyId` bigint NULL DEFAULT NULL COMMENT 'API Key id',
  `modelName` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '使用的模型名称',
  `promptTokens` int NOT NULL DEFAULT 0 COMMENT '输入Token数',
  `completionTokens` int NOT NULL DEFAULT 0 COMMENT '输出Token数',
  `totalTokens` int NOT NULL DEFAULT 0 COMMENT '总Token数',
  `duration` int NOT NULL DEFAULT 0 COMMENT '请求耗时（毫秒）',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '状态：success/failed',
  `errorMessage` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '错误信息',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `cost` decimal(10, 4) NULL DEFAULT 0.0000 COMMENT '本次请求费用（元）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_userId`(`userId` ASC) USING BTREE,
  INDEX `idx_apiKeyId`(`apiKeyId` ASC) USING BTREE,
  INDEX `idx_createTime`(`createTime` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 427328890873708545 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '请求日志' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userAccount` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账号',
  `userPassword` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
  `userName` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户昵称',
  `userAvatar` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户头像',
  `userProfile` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户简介',
  `userRole` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin',
  `editTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT 0 COMMENT '是否删除',
  `tokenQuota` bigint NULL DEFAULT 100000 COMMENT 'Token配额（-1表示无限制）',
  `usedTokens` bigint NULL DEFAULT 0 COMMENT '已使用Token数',
  `userStatus` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'active' COMMENT '用户状态：active-正常，disabled-禁用',
  `balance` decimal(12, 4) NULL DEFAULT 0.0000 COMMENT '账户余额（元）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_userAccount`(`userAccount` ASC) USING BTREE,
  INDEX `idx_userName`(`userName` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 421234959227838465 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
