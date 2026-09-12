package com.lilac.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：直连本机 MySQL 8 + Redis 7，<b>不使用容器</b>。
 *
 * <p>配置来源（优先级从高到低）：
 * <ol>
 *   <li>环境变量 —— CI 里由 {@code .github/workflows/ci.yml} 的 env 块注入</li>
 *   <li>{@code application-test.yml} —— 提交进 git，不含任何密钥</li>
 *   <li>{@code application-dev.yml} —— 只在本机存在（已 gitignore），提供连接串与密码</li>
 * </ol>
 *
 * <p>同时激活 dev + test 两个 profile：本机靠 dev 拿到真实连接信息；
 * CI 里 dev 文件不存在、直接由环境变量顶上。因此本地与 CI 共用同一套测试代码，
 * <b>CI workflow 不需要任何改动</b>。
 *
 * <p><b>前置条件</b>：本机 MySQL 与 Redis 已启动，且 {@code application-dev.yml} 存在。
 * 缺任意一项都会在上下文启动阶段直接报错
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
public abstract class IntegrationTestSupport {
}
