package com.lilac;

import com.lilac.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 上下文冒烟测试。
 *
 * <p>继承 {@link IntegrationTestSupport} 而不是裸用 {@code @SpringBootTest}：
 * 原本它是全项目唯一一个不带 {@code @ActiveProfiles({"dev","test"})} 的 Spring 测试，
 * 结果 Spring 为它单独缓存了一个上下文 —— 而那个上下文加载不到
 * {@code src/test/resources/application-test.yml}，于是
 * <ol>
 *   <li>它带着一个自己的 {@code @Scheduled} 调度器活到整个测试周期结束，
 *       {@code HealthCheckTask} 在别的测试类运行期间照样触发，改写了它们的种子数据
 *       （详见 {@code application-test.yml} 里的说明）；</li>
 *   <li>白多一次完整的上下文启动。</li>
 * </ol>
 * 与其它测试共用同一个上下文后，这两件事一起消失。
 */
class BackendApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}

}
