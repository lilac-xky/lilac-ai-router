package com.lilac.service;

import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.ApiKeyMapper;
import com.lilac.support.IntegrationTestSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API Key 服务测试：创建 / 列表 / 撤销 / 按 Key 值查找 / 用量累加。
 *
 * <h3>为什么是集成测试而不是 Mockito 单测</h3>
 * 这个 service 的每条逻辑最终都落在 {@code QueryWrapper} 上（{@code .eq("isDelete", 0)}、
 * {@code .orderBy("createTime", false)}、逻辑删除自动注入），用 mock 就等于把被测逻辑替换掉。
 * 而且这里有一半的坑是**数据库表结构**造成的（唯一索引缺失、{@code NOT NULL DEFAULT}、列类型），
 * 只有连真库才能暴露 —— 见下面「五个必须知道的表结构事实」。
 *
 * <h3>用 Mapper 造数据，不用被测的 service 造数据</h3>
 * 需要用「脏数据」当输入的场景（{@code totalTokens = NULL}、重复 {@code keyValue}、逻辑删除的行）
 * 一律走 {@link ApiKeyMapper} 直连插入。用 {@code createApiKey} 造数据会造不出这些状态，
 * 而且等于「用被测对象准备自己的输入」。
 *
 * <h3>数据隔离：假 userId + keyName 前缀，不用真实用户</h3>
 * {@code userId} 只用来做「归属校验」，表上没有外键，所以给一个远离真实 id 段的假值
 * （8 开头）即可；cleanup 靠 {@code keyName} 前缀圈定本类造的行，不会碰到真实 API Key。
 *
 * <h3>★ 五个必须知道的表结构事实（全部实测得出，不是读 DDL 猜的）</h3>
 * <ol>
 *   <li><b>{@code keyValue} 上没有唯一索引</b> —— 全表只有 {@code PRIMARY KEY (id)}。
 *       所以「Key 不重复」完全靠 UUID 的随机性，数据库层没有任何兜底。
 *       实测：塞两行同 {@code keyValue} 能成功，且 {@code getByKeyValue} <b>不报错、静默返回其中一条</b>
 *       ⇒ 真撞了的话「用哪个 Key 认证」取决于读取顺序，且没有异常可查。
 *       见 {@link #duplicateKeyValue_isAllowedAndResolvedSilently_documentsWart()}。</li>
 *   <li><b>{@code totalTokens} 是 {@code int NULL DEFAULT NULL}</b> —— 可为空。
 *       而 {@code updateUsageStats} 里是 {@code apiKey.getTotalTokens() + tokens}（自动拆箱）
 *       ⇒ 遇到 {@code NULL} 直接 NPE。见
 *       {@link #updateUsageStats_throwsNpeWhenTotalTokensNull_documentsWart()}。
 *       另外 Java 侧字段是 {@code Long}、库里是 {@code int}（32 位），
 *       超过 21 亿 token 会溢出 —— 本测试不验（造不出来），但记在这里。</li>
 *   <li><b>{@code lastUsedTime} 是 {@code NOT NULL DEFAULT CURRENT_TIMESTAMP}</b> ——
 *       「最后使用时间」在「从未使用过」时本该是 NULL，却被 DDL 强制填成插入时刻。
 *       实测：新建 Key 的 {@code lastUsedTime} ≈ 创建时刻（差 0~1 秒，因为一个来自 DB 的
 *       {@code CURRENT_TIMESTAMP}、一个来自 Java 的 {@code LocalDateTime.now()} 再截断到秒，
 *       <b>两个时钟源不同</b>，所以甚至可能早于 {@code createTime}）。
 *       见 {@link #createApiKey_lastUsedTimeIsCreationMoment_documentsWart()}。</li>
 *   <li><b>{@code createTime} 是秒级精度（{@code datetime} 不带小数位）</b> ——
 *       同一秒内创建的几行 {@code createTime} 完全相同，而 {@code listUserApiKeys} 只
 *       {@code orderBy("createTime", false)}、<b>没有 tie-breaker</b>
 *       ⇒ 排序断言会「走运绿」。因此本测试造行时<b>显式写入相差 5 秒的 createTime</b>，
 *       绝不用「连续调三次 service 然后断言顺序」那种写法。</li>
 *   <li><b>逻辑删除的行物理上永远留在表里</b> —— {@code deleteById} 只是 {@code UPDATE isDelete=1}。
 *       实测：逻辑删除后 {@code selectOneById} / {@code getByKeyValue} 都查不到（业务侧正确），
 *       但 {@code SELECT COUNT(*)} 数出来行还在。⇒ 测试清理必须物理删除，否则跑一次攒一批。
 *       见 {@link #cleanUp()}。</li>
 * </ol>
 *
 * <h3>怎么读这个类的日志</h3>
 * 每个用例只打一行「<b>实际结果</b>」，且放在断言之前（断言挂了这行也已输出）：
 * <pre>
 * [创建·a1b2c3d4] keyValue=sk-xxxx…（35 位） status=active totalTokens=0 已落库=true
 * </pre>
 * 沿用 {@code RoutingStrategyTest} / {@code RateLimitTest} 的约定：
 * <b>{@code @BeforeEach} 适合造数据，不适合打日志</b> —— 只要一行日志的内容不随用例变化，
 * 它就会重复 N 份，等于没打。
 *
 * <h3>★ 两组用例，读的时候要分清</h3>
 * <ul>
 *   <li><b>A 组</b>（前 9 个）—— 断言的是<b>期望语义</b>，绿 = 这块是对的。</li>
 *   <li><b>B 组</b>（名字带 {@code _documentsWart}）—— 断言的是<b>当前实现的实际行为</b>，
 *       绿<b>不代表</b>这是对的，只代表「缺陷已被钉住、修改时会有感应」。
 *       每条都在方法注释里写了「该修的是什么」和「修好之后断言该怎么改」。
 *       把它们改成绿色期望值 = 修好了；改动实现时这两个方向必须一起动。</li>
 * </ul>
 * B 组的缺陷不在 W3 修（W3 的定位是「写复现问题的测试」），归属周次见
 * {@code docs/issue-ApiKey与密钥管理.md}。
 */
@Slf4j
public class ApiKeyServiceTest extends IntegrationTestSupport {

    /** 本类造的所有行的 keyName 都以它开头 —— cleanup 靠它圈范围，绝不碰真实 API Key */
    private static final String MARKER = "ak-test-";

    /** 残留清理每个 JVM 只做一次 */
    private static final AtomicBoolean STALE_PURGED = new AtomicBoolean(false);

    @Resource
    private ApiKeyService apiKeyService;
    @Resource
    private ApiKeyMapper apiKeyMapper;
    @Resource
    private DataSource dataSource;

    private String runId;
    private Long testUserId;
    /** 另一个用户 —— 用于验证「不能撤销别人的 Key」 */
    private Long otherUserId;

    // ------------------------------------------------------------------ 数据准备

    @BeforeEach
    void setUp() {
        purgeStaleRows();

        runId = UUID.randomUUID().toString().substring(0, 8);
        // 8 开头的 id 段远离真实用户（真实用户是雪花 id），撞不上
        testUserId = 8_000_000_000L + Math.abs(runId.hashCode() % 100_000);
        otherUserId = testUserId + 1;
    }

    @AfterEach
    void cleanUp() {
        int deleted = physicallyDelete(MARKER + runId + "%");
        if (deleted > 0) {
            log.debug("[清理·{}] 删除 {} 行 API Key", runId, deleted);
        }
        // 自检：残留会在下次跑时变成幽灵数据，症状是「单独跑绿、全量跑红」，极难定位 → 就地炸掉
        assertThat(countByKeyNamePattern(MARKER + runId + "%"))
                .as("清理后仍有残留行，keyName LIKE '%s%s%%'", MARKER, runId)
                .isZero();
    }

    /** 兜底：上次被 IDE 强停（@AfterEach 没跑）留下的垃圾。只清本类命名空间 */
    private void purgeStaleRows() {
        if (STALE_PURGED.compareAndSet(false, true)) {
            int n = physicallyDelete(MARKER + "%");
            if (n > 0) {
                log.info("清掉上次运行残留的 {} 行 API Key 测试数据", n);
            }
        }
    }

    // ================================================================== A 组：期望语义

    @Test
    @DisplayName("创建 · sk- 前缀 + 32 位随机 + active + totalTokens 归零，并真的落库")
    void createApiKey_buildsExpectedKeyAndPersists() {
        ApiKey created = apiKeyService.createApiKey(MARKER + runId + "-a1", user(testUserId));
        ApiKey fromDb = apiKeyMapper.selectOneById(created.getId());

        log.info("[创建·{}] keyValue={}…（共 {} 位） status={} totalTokens={} isDelete={} 已落库={}",
                runId, created.getKeyValue().substring(0, 11), created.getKeyValue().length(),
                created.getStatus(), created.getTotalTokens(), created.getIsDelete(), fromDb != null);

        assertThat(created.getId()).as("雪花 id 应由框架生成").isNotNull();
        assertThat(created.getKeyValue())
                .as("keyValue 必须是 sk- + 32 位小写 hex，不能有别的字符")
                .matches("^sk-[0-9a-f]{32}$");
        assertThat(created.getUserId()).as("归属必须取自 loginUser").isEqualTo(testUserId);
        assertThat(created.getStatus()).as("新建的 Key 必须是 active").isEqualTo("active");
        assertThat(created.getTotalTokens()).as("初始用量必须是 0 而不是 null").isZero();

        assertThat(fromDb).as("创建后必须能从库里查回来").isNotNull();
        assertThat(fromDb.getKeyValue()).isEqualTo(created.getKeyValue());
        assertThat(fromDb.getIsDelete()).as("isDelete 必须是 0，否则刚建的 Key 自己就查不到").isZero();
    }

    @Test
    @DisplayName("创建 · 名称是 null 或空串时拒绝")
    void createApiKey_rejectsNullOrEmptyName() {
        User u = user(testUserId);
        Integer nullCode = codeOf(() -> apiKeyService.createApiKey(null, u));
        Integer emptyCode = codeOf(() -> apiKeyService.createApiKey("", u));

        log.info("[创建·校验·{}] keyName=null → code={} | keyName=\"\" → code={}",
                runId, nullCode, emptyCode);

        assertThat(nullCode).as("null 名称必须被拒").isEqualTo(HttpsCodeEnum.PARAMS_ERROR.getCode());
        assertThat(emptyCode).as("空串名称必须被拒").isEqualTo(HttpsCodeEnum.PARAMS_ERROR.getCode());
    }

    @Test
    @DisplayName("列表 · 只返回自己的、未删除的 Key，且按 createTime 倒序")
    void listUserApiKeys_returnsOnlyOwnKeysNewestFirst() {
        // 显式写入相差 5 秒的 createTime：秒级精度下同秒创建的几行顺序不稳定，
        // 「连续调三次 service 再断言顺序」是走运绿（见类注释事实 4）
        LocalDateTime base = LocalDateTime.now().withNano(0).minusMinutes(1);
        insert(newSeed("old", testUserId, "active", 0L).createTime(base.minusSeconds(10)).build());
        insert(newSeed("new", testUserId, "active", 0L).createTime(base).build());
        // 干扰项 1：自己的、但已逻辑删除
        insert(newSeed("deleted", testUserId, "active", 0L).createTime(base.minusSeconds(5)).isDelete(1).build());
        // 干扰项 2：别人的
        insert(newSeed("others", otherUserId, "active", 0L).createTime(base).build());

        List<ApiKey> mine = apiKeyService.listUserApiKeys(testUserId);

        log.info("[列表·{}] 共 4 行（含已删 1 行 + 别人的 1 行）→ 返回 {} 行：{}",
                runId, mine.size(), mine.stream().map(k -> brief(k.getKeyName())).collect(Collectors.joining(" → ")));

        assertThat(mine).as("逻辑删除的、别人的都不能出现").hasSize(2);
        assertThat(mine).extracting(ApiKey::getKeyName)
                .containsExactly(MARKER + runId + "-new", MARKER + runId + "-old");
    }

    @Test
    @DisplayName("撤销 · status 置为 revoked 并落库")
    void revokeApiKey_marksRevoked() {
        ApiKey k = apiKeyService.createApiKey(MARKER + runId + "-r1", user(testUserId));

        boolean result = apiKeyService.revokeApiKey(k.getId(), testUserId);
        String status = apiKeyMapper.selectOneById(k.getId()).getStatus();

        log.info("[撤销·{}] 返回值={} 库中 status={}", runId, result, status);

        assertThat(result).as("撤销应返回成功").isTrue();
        assertThat(status).as("撤销后 status 必须是 revoked").isEqualTo("revoked");
    }

    @Test
    @DisplayName("越权 · 撤销别人的 Key 必须失败，且绝不能误改别人的数据")
    void revokeApiKey_rejectsKeyOfAnotherUser() {
        ApiKey victimsKey = apiKeyService.createApiKey(MARKER + runId + "-v1", user(otherUserId));

        Integer code = codeOf(() -> apiKeyService.revokeApiKey(victimsKey.getId(), testUserId));
        String statusAfter = apiKeyMapper.selectOneById(victimsKey.getId()).getStatus();

        log.info("[越权·{}] 用自己的 userId 撤销别人的 Key → code={} | 对方 Key 的 status={}（必须仍是 active）",
                runId, code, statusAfter);

        assertThat(code).as("跨用户撤销必须报「不存在」而不是「无权限」，不泄露 Key 是否真实存在")
                .isEqualTo(HttpsCodeEnum.NOT_FOUND_ERROR.getCode());
        // 只断言抛异常是不够的：如果实现是「先改再校验」，异常照样抛，但数据已经被改了。
        // 所以必须再断言一次「别人的 Key 没被动过」—— 这是越权测试真正有价值的那一半。
        assertThat(statusAfter).as("越权尝试不得对别人的数据产生任何副作用").isEqualTo("active");
    }

    @Test
    @DisplayName("撤销 · 不存在的 id 报 NOT_FOUND")
    void revokeApiKey_rejectsUnknownId() {
        Integer code = codeOf(() -> apiKeyService.revokeApiKey(-1L, testUserId));
        log.info("[撤销·不存在·{}] id=-1 → code={}", runId, code);
        assertThat(code).isEqualTo(HttpsCodeEnum.NOT_FOUND_ERROR.getCode());
    }

    @Test
    @DisplayName("密钥失效 · getByKeyValue 对已撤销的 Key 返回 null（撤销立即生效）")
    void getByKeyValue_returnsNullAfterRevoke() {
        ApiKey k = apiKeyService.createApiKey(MARKER + runId + "-e1", user(testUserId));
        boolean foundBefore = apiKeyService.getByKeyValue(k.getKeyValue()) != null;

        apiKeyService.revokeApiKey(k.getId(), testUserId);
        boolean foundAfter = apiKeyService.getByKeyValue(k.getKeyValue()) != null;

        log.info("[失效·{}] 撤销前查得到={} → 撤销后查得到={}（必须 false，否则撤销形同虚设）",
                runId, foundBefore, foundAfter);

        assertThat(foundBefore).as("前置：撤销前必须能查到").isTrue();
        assertThat(foundAfter).as("撤销必须立即生效，不能等到缓存过期").isFalse();
    }

    @Test
    @DisplayName("密钥失效 · getByKeyValue 查不到逻辑删除的行（但物理行还在）")
    void getByKeyValue_ignoresLogicDeleted() {
        ApiKey row = insert(newSeed("d1", testUserId, "active", 0L).build());
        apiKeyMapper.deleteById(row.getId());

        ApiKey found = apiKeyService.getByKeyValue(row.getKeyValue());
        long physicalRows = countByKeyNamePattern(MARKER + runId + "-d1");

        log.info("[逻辑删除·{}] getByKeyValue={} | 库里物理行数={}（逻辑删除不删行，所以清理必须物理删）",
                runId, found == null ? "null" : "命中", physicalRows);

        assertThat(found).as("逻辑删除的 Key 不能用来认证").isNull();
        assertThat(physicalRows).as("逻辑删除只是 UPDATE isDelete=1，行仍在表里").isEqualTo(1);
    }

    @Test
    @DisplayName("用量 · updateUsageStats 累加 token 并刷新 lastUsedTime")
    void updateUsageStats_accumulatesTokens() {
        ApiKey k = apiKeyService.createApiKey(MARKER + runId + "-u1", user(testUserId));

        apiKeyService.updateUsageStats(k.getId(), 5);
        apiKeyService.updateUsageStats(k.getId(), 2);
        ApiKey after = apiKeyMapper.selectOneById(k.getId());

        log.info("[用量·{}] 连续 +5、+2 → totalTokens={} lastUsedTime={}",
                runId, after.getTotalTokens(), after.getLastUsedTime());

        assertThat(after.getTotalTokens()).as("两次累加结果应为 7（不是覆盖成 2）").isEqualTo(7L);
        assertThat(after.getLastUsedTime()).as("用过之后 lastUsedTime 必须有值").isNotNull();
    }

    // ================================================================== B 组：已知缺陷（断言当前行为）

    @Test
    @DisplayName("已知缺陷 · 刚创建的 Key，lastUsedTime 就是创建时刻")
    void createApiKey_lastUsedTimeIsCreationMoment_documentsWart() {
        ApiKey created = apiKeyService.createApiKey(MARKER + runId + "-w1", user(testUserId));
        ApiKey fromDb = apiKeyMapper.selectOneById(created.getId());

        Duration gap = Duration.between(fromDb.getCreateTime(), fromDb.getLastUsedTime()).abs();
        log.info("[缺陷·lastUsedTime·{}] 返回对象里={} | 库里={}（createTime={}，相差 {} 秒）⇒ 从未使用过却显示「刚用过」",
                runId, created.getLastUsedTime(), fromDb.getLastUsedTime(), fromDb.getCreateTime(), gap.toSeconds());

        // ⚠️ 断言的是【当前实现的行为】，不是期望语义。
        // 成因：DDL 写的是 `lastUsedTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP`。
        // 「最后使用时间」在「从未使用过」时的正确表达是 NULL，DDL 却强制填了插入时刻。
        // 后果：任何「这个 Key 最近有没有在被用」的判断都是错的 —— 新建的 Key 也显示刚用过，
        //      于是清理未使用 Key、安全审计、活跃度统计全部失真。
        //       这跟 docs/issue-模型评分与健康检查.md 问题一（无数据被填成满分）是同一类毛病：
        //       **拿一个合法值去表示「没有数据」**。
        // 该修的：DDL 改成允许 NULL 且不给 DEFAULT；service 侧不填这个字段。
        // 修好后本用例应改为断言 fromDb.getLastUsedTime() 为 null。
        assertThat(created.getLastUsedTime())
                .as("返回对象里是 null（service 没填）")
                .isNull();
        assertThat(fromDb.getLastUsedTime())
                .as("但落库后被 DDL 默认值填上了 —— 缺陷，不是期望行为")
                .isNotNull();
        assertThat(gap.toSeconds())
                .as("而且填的正是「创建时刻」，所以看起来像刚被使用过")
                .isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("已知缺陷 · keyName 只拦 null 和空串，拦不住纯空格")
    void createApiKey_acceptsWhitespaceOnlyName_documentsWart() {
        String blank = "   ";
        ApiKey created = apiKeyService.createApiKey(blank, user(testUserId));
        String stored = apiKeyMapper.selectOneById(created.getId()).getKeyName();

        log.info("[缺陷·keyName·{}] keyName=\"{}\" → 被接受，落库为 \"{}\"（长度 {}）",
                runId, blank, stored, stored.length());

        // ⚠️ 断言的是【当前实现的行为】。校验条件是 `keyName == null || keyName.isEmpty()`，
        // 空串被拦住了，但纯空格 / 制表符 / 全角空格都能过。
        // 后果：列表里会出现一堆看不见名字的 Key，用户无法辨认、也无法删除指定哪个。
        // 该修的：改成 `keyName == null || keyName.isBlank()`，并 trim 后落库。
        // 修好后本用例应改为断言抛 PARAMS_ERROR。
        assertThat(stored).as("纯空格被原样存入（缺陷，不是期望行为）").isEqualTo(blank);
        assertThat(blank.isEmpty()).as("对照：isEmpty() 为 false，所以确实绕过了校验").isFalse();
        assertThat(blank.isBlank()).as("对照：isBlank() 为 true —— 正确的校验应该用它").isTrue();
    }

    @Test
    @DisplayName("已知缺陷 · totalTokens 为 NULL 时 updateUsageStats 抛 NPE")
    void updateUsageStats_throwsNpeWhenTotalTokensNull_documentsWart() {
        // DDL 是 `totalTokens int NULL DEFAULT NULL`，所以 NULL 是合法存量状态
        ApiKey row = insert(newSeed("n1", testUserId, "active", null).build());

        log.info("[缺陷·NPE·{}] totalTokens=NULL 时调用 updateUsageStats → 抛 NPE（自动拆箱 getTotalTokens() + tokens）", runId);

        // ⚠️ 断言的是【当前实现的行为】。`apiKey.getTotalTokens() + tokens` 里 getTotalTokens() 是 Long，
        // 与 int 相加会自动拆箱 → 遇到 NULL 直接 NPE，异常冒到切面外变成 500。
        // 后果：只要库里有任何一行 totalTokens 是 NULL（历史数据、别的写入路径、手工插入），
        //      调用它的那次对话请求就 500 —— 而且和「这个 Key 本身」看起来毫无关系，极难定位。
        // 该修的：`(apiKey.getTotalTokens() == null ? 0L : apiKey.getTotalTokens()) + tokens`。
        // 注意 controller 的 getMyTokenStats 已经这么兜了，说明这个坑在别处已经踩过一次 —— service 里漏了。
        assertThatThrownBy(() -> apiKeyService.updateUsageStats(row.getId(), 5))
                .as("NULL 用量 + 累加 = NPE（缺陷，不是期望行为）")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("getTotalTokens");

        // 对照：把它补成 0 之后就正常了 —— 这也是存量数据的临时补救办法
        ApiKey fixed = apiKeyMapper.selectOneById(row.getId());
        fixed.setTotalTokens(0L);
        apiKeyMapper.update(fixed);
        apiKeyService.updateUsageStats(row.getId(), 5);
        assertThat(apiKeyMapper.selectOneById(row.getId()).getTotalTokens())
                .as("补 0 后累加正常")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("已知缺陷 · keyValue 没有唯一索引，重复时静默取其中一条")
    void duplicateKeyValue_isAllowedAndResolvedSilently_documentsWart() {
        String dup = "sk-dup-" + runId;
        ApiKey a = insert(newSeed("dup-a", testUserId, "active", 0L).keyValue(dup).build());
        ApiKey b = insert(newSeed("dup-b", otherUserId, "active", 0L).keyValue(dup).build());

        ApiKey resolved = apiKeyService.getByKeyValue(dup);
        long rows = countByKeyValue(dup);

        log.info("[缺陷·重复密钥·{}] 同 keyValue 的物理行数={} → getByKeyValue 静默返回 {}（未抛异常）",
                runId, rows, resolved == null ? "null" : brief(resolved.getKeyName()));

        // ⚠️ 断言的是【当前实现的行为】。DDL 上 keyValue 没有任何唯一索引，全表只有主键。
        // 「Key 不重复」全靠 IdUtil.simpleUUID() 的随机性 —— 32 位 hex，碰撞概率极低，但**架构上没有兜底**。
        // 一旦碰撞（或有人手工插数据 / 未来换成可预测的生成方式），后果是：
        //   getByKeyValue 静默返回其中一条，**认证到哪个用户取决于读取顺序**，
        //   而且没有任何异常、没有日志 —— 一个用户会拿着另一个用户的 Key 通过认证。
        // 该修的：给 keyValue 加唯一索引（同时把 Key 改成哈希存储，见 issue 文档），
        //        并让 createApiKey 在冲突时重试生成。
        // 修好后：插入重复 keyValue 本身就该失败，本用例整体改写。
        assertThat(rows).as("数据库允许两行同 keyValue（缺陷，不是期望行为）").isEqualTo(2);
        assertThat(resolved).as("而且不报错，静默返回一条 —— 比直接报错更危险").isNotNull();
        assertThat(resolved.getKeyName())
                .as("返回的是先插入的那条（无 ORDER BY，实际取决于存储顺序）")
                .isIn(a.getKeyName(), b.getKeyName());
        assertThat(a.getKeyName()).isNotEqualTo(b.getKeyName());
    }

    @Test
    @DisplayName("已知缺陷 · 撤销可被「陈旧快照写回」静默回滚，已撤销的 Key 会复活")
    void staleSnapshotWriteBack_revertsRevoke_documentsWart() {
        ApiKey k = apiKeyService.createApiKey(MARKER + runId + "-s1", user(testUserId));

        // 把并发窗口按顺序摆出来（确定性演示，不用真并发碰运气）：
        // ① 另一个请求先读到了这行（此时 status=active）
        ApiKey staleSnapshot = apiKeyMapper.selectOneById(k.getId());
        // ② 撤销在这中间提交了
        apiKeyService.revokeApiKey(k.getId(), testUserId);
        String statusAfterRevoke = apiKeyMapper.selectOneById(k.getId()).getStatus();
        // ③ 那个请求现在才写回它读到的整行 —— 这一步正是 updateUsageStats 的第 3 行 `this.updateById(apiKey)`
        apiKeyMapper.update(staleSnapshot);

        ApiKey after = apiKeyMapper.selectOneById(k.getId());
        ApiKey stillUsable = apiKeyService.getByKeyValue(k.getKeyValue());

        log.info("[缺陷·撤销被回滚·{}] revoke 后 status={} → 陈旧写回后 status={} | 该 Key 还能认证={}",
                runId, statusAfterRevoke, after.getStatus(), stillUsable != null);

        // ⚠️ 断言的是【当前实现的行为】。updateUsageStats 是「读整行 → 改 token → 写整行」，
        // 第 3 步的 updateById 会把这行**所有非 null 字段**一起写回（实测 updateById 忽略 null，
        // 但 status / keyValue 都是非 null 的，所以会被覆盖）。
        // 后果：并发下「撤销」会被「记用量」无声抹掉 —— 用户点了撤销，看起来成功了（返回 true、
        //      界面显示已撤销），后台一个并发的对话请求把 status 写回 active，这个 Key 继续能用。
        //      **安全操作被普通计费操作回滚**，属于最不该发生的一类竞态。
        // 该修的：updateUsageStats 改用「只更新用量这一列」的定向 SQL
        //      （如 `UPDATE api_key SET totalTokens = totalTokens + ?, lastUsedTime = NOW() WHERE id = ?`
        //       走原子累加，而不是读-改-写整行）—— 和 W6「扣费原子性」是同一类问题：
        //      **用「读整行再写回整行」实现计数，必然丢更新**。
        // 修好后本用例应改为断言 status 仍是 revoked、且 getByKeyValue 返回 null。
        assertThat(statusAfterRevoke).as("前置：撤销本身是成功的").isEqualTo("revoked");
        assertThat(after.getStatus())
                .as("陈旧写回把 revoked 覆盖成了 active（缺陷，不是期望行为）")
                .isEqualTo("active");
        assertThat(stillUsable)
                .as("已撤销的 Key 重新可用了 —— 这才是这条缺陷真正的后果")
                .isNotNull();
        assertThat(stillUsable.getStatus()).isEqualTo("active");
    }

    // ================================================================== helpers

    /** 直连 Mapper 造行 —— 不用被测的 service 造数据（见类注释） */
    private ApiKey insert(ApiKey row) {
        apiKeyMapper.insertSelective(row);
        return row;
    }

    /**
     * 造行模板。keyName 一定带 MARKER + runId ⇒ cleanup 能圈住；
     * userId 由调用方指定，用来验证隔离。
     */
    private ApiKey.ApiKeyBuilder newSeed(String suffix, Long userId, String status, Long totalTokens) {
        LocalDateTime now = LocalDateTime.now();
        return ApiKey.builder()
                .userId(userId)
                .keyValue("sk-seed-" + runId + "-" + suffix)
                .keyName(MARKER + runId + "-" + suffix)
                .status(status)
                .totalTokens(totalTokens)
                .createTime(now)
                .updateTime(now);
    }

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    /** 取 BusinessException 的 code；没抛异常返回 null（这样失败信息比「没抛异常」更有用） */
    private Integer codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException e) {
            return e.getCode();
        }
    }

    private String brief(String keyName) {
        return keyName == null ? "<null>" : keyName.replace(MARKER + runId + "-", "");
    }

    private long countByKeyNamePattern(String pattern) {
        return countRows("keyName LIKE ?", pattern);
    }

    private long countByKeyValue(String keyValue) {
        return countRows("keyValue = ?", keyValue);
    }

    private long countRows(String where, String param) {
        String sql = "SELECT COUNT(*) FROM `api_key` WHERE " + where;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("统计 api_key 行数失败（where={}, param={}）", where, param, e);
            return -1;
        }
    }

    /**
     * 物理删除测试行。
     *
     * <p>不能走 {@code apiKeyMapper.deleteById} —— 那是逻辑删除（实测行永远留在表里）。
     * 清理失败只告警不抛：不该让一个本来绿了的用例因为清理失败变红。
     */
    private int physicallyDelete(String keyNamePattern) {
        String sql = "DELETE FROM `api_key` WHERE keyName LIKE ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyNamePattern);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("物理清理 api_key 测试数据失败（pattern={}），需手工执行："
                    + "DELETE FROM `api_key` WHERE keyName LIKE 'ak-test-%'", keyNamePattern, e);
            return 0;
        }
    }
}
