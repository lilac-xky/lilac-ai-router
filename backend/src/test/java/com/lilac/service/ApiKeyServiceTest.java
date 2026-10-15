package com.lilac.service;

import com.lilac.domain.entity.ApiKey;
import com.lilac.domain.entity.User;
import com.lilac.enums.HttpsCodeEnum;
import com.lilac.exception.BusinessException;
import com.lilac.mapper.ApiKeyMapper;
import com.lilac.support.IntegrationTestSupport;
import com.lilac.utils.ApiKeyHasher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li><b>{@code keyHash} 上有唯一索引 {@code uk_keyHash}（V1002 新增）</b> ——
 *       而 V1002 之前 {@code keyValue} 上<b>没有任何唯一索引</b>，全表只有 {@code PRIMARY KEY (id)}，
 *       「Key 不重复」完全靠 UUID 的随机性。当时的实测：塞两行同 {@code keyValue} 能成功，
 *       且 {@code getByKeyValue} <b>不报错、静默返回其中一条</b> ⇒ 「A 拿自己的 Key 被认证成 B」。
 *       <b>已修</b>：改成哈希存储 + 唯一索引，回归见
 *       {@link #duplicateKeyHash_isRejectedByUniqueIndex()}。</li>
 *   <li><b>{@code totalTokens} 是 {@code int NULL DEFAULT NULL}</b> —— 可为空。
 *       ⚠️ 这一条曾经是真缺陷：{@code apiKey.getTotalTokens() + tokens} 会自动拆箱，
 *       遇到 {@code NULL} 直接 NPE，一次对话请求就 500。
 *       <b>已修</b>（{@code updateUsageStats} 改成定向原子 UPDATE，NULL 由 SQL 的
 *       {@code COALESCE} 兜住），回归见 {@link #updateUsageStats_accumulatesFromNullTokens()}。
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
 *   <li><b>A 组</b> —— 断言的是<b>期望语义</b>，绿 = 这块是对的。</li>
 *   <li><b>B 组</b>（名字带 {@code _documentsWart}）—— 断言的是<b>当前实现的实际行为</b>，
 *       绿<b>不代表</b>这是对的，只代表「缺陷已被钉住、修改时会有感应」。
 *       每条都在方法注释里写了「该修的是什么」和「修好之后断言该怎么改」。
 *       把它们改成绿色期望值 = 修好了；改动实现时这两个方向必须一起动。</li>
 * </ul>
 *
 * <h3>★ W6 已修掉的两条（原 B 组用例已转正为回归）</h3>
 * <ol>
 *   <li><b>撤销被静默回滚</b> —— 原先由 {@code staleSnapshotWriteBack_revertsRevoke_documentsWart} 钉住，
 *       现已修掉（{@code updateUsageStats} 不再回写整行），用例转为
 *       {@link #updateUsageStats_doesNotRevertRevoke()} 断言「status 保持 revoked」；</li>
 *   <li><b>{@code totalTokens} 为 NULL 时 NPE</b> —— 原先由
 *       {@code updateUsageStats_throwsNpeWhenTotalTokensNull_documentsWart} 钉住，
 *       现已修掉（NULL 由 SQL 的 {@code COALESCE} 兜住），用例转为
 *       {@link #updateUsageStats_accumulatesFromNullTokens()}。</li>
 * </ol>
 * 两者与 W6「扣费原子性」同一根因：<b>用「读整行再写回整行」实现计数，必然丢更新</b>。
 * 原 B 组里两条 DDL / 安全语义的用例（{@code keyValue} 无唯一索引、明文存储）**已由 W8 的 V1002 修掉**，
 * 转为期望语义用例 {@link #duplicateKeyHash_isRejectedByUniqueIndex()} /
 * {@link #createApiKey_storesHashNotPlaintext()}。
 * {@code lastUsedTime} 与 {@code keyName} 纯空格两条仍按当前行为钉住（见 issue 文档）。
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
    @Resource
    private PlatformTransactionManager transactionManager;

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
        // 库里不再有明文（V1002 已把 keyValue 置空，且实体上标了 @Column(ignore=true) 不参与读写），
        // 所以这里改成断言「库里那行的哈希 == 返回明文的哈希」——这才是现在真正该成立的事
        assertThat(fromDb.getKeyValue()).as("明文绝不落库").isNull();
        assertThat(fromDb.getKeyHash())
                .as("库里的哈希必须与创建时返回的明文对得上，否则用户拿到的 Key 当场就是废的")
                .isEqualTo(ApiKeyHasher.sha256Hex(created.getKeyValue()));
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
        String plain = "sk-seed-" + runId + "-d1";
        ApiKey row = insert(newSeed("d1", testUserId, "active", 0L).build());
        apiKeyMapper.deleteById(row.getId());

        ApiKey found = apiKeyService.getByKeyValue(plain);
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
    @DisplayName("创建 · 纯空格名称被拒绝（✅ V1002 已修：原先 isEmpty() 拦不住空格）")
    void createApiKey_rejectsWhitespaceOnlyName() {
        String blank = "   ";
        Integer blankCode = codeOf(() -> apiKeyService.createApiKey(blank, user(testUserId)));
        Integer tabCode = codeOf(() -> apiKeyService.createApiKey("\t\n ", user(testUserId)));

        log.info("[创建·空白名·{}] keyName=\"   \" → code={} | 制表换行混排 → code={}",
                runId, blankCode, tabCode);

        // ⚠️ 原实现是 `keyName == null || keyName.isEmpty()`，空串拦住了但纯空格能过，
        //    于是列表里会出现看不见名字的 Key，用户无法辨认也无法指定删除哪个。
        //    已改为 StrUtil.isBlank() 并在落库前 trim。
        assertThat(blankCode).as("纯空格必须被拒").isEqualTo(HttpsCodeEnum.PARAMS_ERROR.getCode());
        assertThat(tabCode).as("制表符/换行也算空白，必须一并拒绝").isEqualTo(HttpsCodeEnum.PARAMS_ERROR.getCode());
    }

    @Test
    @DisplayName("创建 · 名称两端空白会被 trim 掉（避免「看不见的 Key 名」）")
    void createApiKey_trimsName() {
        // 名称合法但两端带空白：应保留内容、去掉空白
        ApiKey created = apiKeyService.createApiKey("  " + MARKER + runId + "-trim  ", user(testUserId));
        String stored = apiKeyMapper.selectOneById(created.getId()).getKeyName();

        log.info("[创建·trim·{}] 落库的 keyName=\"{}\"（长度 {}）", runId, stored, stored.length());

        assertThat(stored)
                .as("两端空白必须被去掉，否则列表里名字前后带看不见的空格")
                .isEqualTo(MARKER + runId + "-trim");
    }

    @Test
    @DisplayName("用量 · totalTokens 为 NULL 时也能正常累加（回归：原先抛 NPE）")
    void updateUsageStats_accumulatesFromNullTokens() {
        // DDL 是 `totalTokens int NULL DEFAULT NULL`，所以 NULL 是合法存量状态
        ApiKey row = insert(newSeed("n1", testUserId, "active", null).build());

        apiKeyService.updateUsageStats(row.getId(), 5);
        ApiKey after = apiKeyMapper.selectOneById(row.getId());

        log.info("[用量·NULL·{}] totalTokens=NULL + 5 → {}（不再抛 NPE）", runId, after.getTotalTokens());

        // ✅ 期望语义。修复前这里是 NPE：`apiKey.getTotalTokens() + tokens` 里 getTotalTokens()
        //    是 Long，与 int 相加会自动拆箱 → 遇到 NULL 直接 NPE，异常冒到切面外变成 500。
        //    后果是：只要库里有任何一行 totalTokens 是 NULL（历史数据、别的写入路径、手工插入），
        //    调用它的那次对话请求就 500 —— 而且报错指向 ApiKey.getTotalTokens()，
        //    和「这个用户当时在做什么」看起来毫无关系，极难定位。
        //    现在 NULL 由 SQL 里的 COALESCE(totalTokens, 0) 兜住，不再进入 Java 的拆箱路径。
        assertThat(after.getTotalTokens()).as("NULL 应被当成 0 再加 5").isEqualTo(5L);

        // 再累加一次，确认不是「覆盖成 5」而是真的继续往上加
        apiKeyService.updateUsageStats(row.getId(), 3);
        assertThat(apiKeyMapper.selectOneById(row.getId()).getTotalTokens())
                .as("NULL 起步后继续累加应为 8")
                .isEqualTo(8L);
    }

    @Test
    @DisplayName("密钥唯一 · 重复 keyHash 被唯一索引挡住（✅ V1002 已修：原先允许重复且静默取一条）")
    void duplicateKeyHash_isRejectedByUniqueIndex() {
        ApiKey a = insert(newSeed("dup-a", testUserId, "active", 0L).build());
        String sameHash = a.getKeyHash();

        log.info("[唯一·{}] 第一行 keyHash={}…，尝试插入相同哈希的第二行", runId, sameHash.substring(0, 12));

        // ⚠️ V1002 之前这里会成功，且 getByKeyValue 不报错、静默返回其中一条 ——
        //    「A 拿自己的 Key 被认证成 B」的直接来源（无 ORDER BY，取决于存储顺序）。
        //    ⚠️ 必须复用第一行真实的 keyHash：早先这版自己拼了个字符串去算哈希，
        //    结果两行哈希根本不同，插入当然成功，用例变成「什么都没测」。
        assertThatThrownBy(() -> insert(newSeed("dup-b", otherUserId, "active", 0L)
                .keyHash(sameHash)
                .build()))
                .as("重复 keyHash 必须被唯一索引拒绝，而不是静默插入")
                .isInstanceOf(Exception.class);

        assertThat(countByKeyHash(sameHash)).as("物理上只能有一行持有该哈希").isEqualTo(1);
        assertThat(a.getId()).isNotNull();
    }

    @Test
    @DisplayName("密钥已哈希 · 库里不存明文，且明文只在创建返回时出现一次")
    void createApiKey_storesHashNotPlaintext() {
        ApiKey created = apiKeyService.createApiKey(MARKER + runId + "-h1", user(testUserId));
        String plain = created.getKeyValue();

        log.info("[已哈希·{}] 创建返回明文={}… 库中 keyHash={}… keyPrefix={} 库里明文列={}",
                runId, plain.substring(0, 11), created.getKeyHash().substring(0, 12),
                created.getKeyPrefix(), "（应为 null）");

        assertThat(plain).as("创建时必须把明文返回给用户一次（否则用户拿不到 Key）")
                .startsWith("sk-").hasSize(35);
        assertThat(created.getKeyHash())
                .as("落库的必须是 SHA-256，不是明文").isEqualTo(ApiKeyHasher.sha256Hex(plain)).hasSize(64);
        assertThat(created.getKeyPrefix()).as("前缀取明文前 8 位").isEqualTo(plain.substring(0, 8));

        // 从库里重新读一遍，确认明文没有落到 keyValue 列
        ApiKey fromDb = apiKeyMapper.selectOneById(created.getId());
        assertThat(fromDb.getKeyHash()).as("哈希已落库").isEqualTo(ApiKeyHasher.sha256Hex(plain));
        assertThat(fromDb.getKeyValue()).as("明文绝不能落库（实体上是 @Column(ignore=true)）").isNull();

        // 而用它鉴权仍然要能查到 —— 哈希存储不能把功能弄坏
        assertThat(apiKeyService.getByKeyValue(plain))
                .as("按明文鉴权仍应命中同一行").isNotNull()
                .extracting(ApiKey::getId).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("轮换 · 换发新 Key，旧 Key 立即失效，名称与用量统计保留（哈希不可逆的配套能力）")
    void rotateApiKey_issuesNewKeyAndInvalidatesOld() {
        ApiKey created = apiKeyService.createApiKey(MARKER + runId + "-r1", user(testUserId));
        String oldPlain = created.getKeyValue();
        apiKeyService.updateUsageStats(created.getId(), 7);

        ApiKey rotated = apiKeyService.rotateApiKey(created.getId(), testUserId);
        String newPlain = rotated.getKeyValue();

        log.info("[轮换·{}] 旧={}… → 新={}… | 旧 Key 还能用={} | 新 Key 能用={} | totalTokens={} keyName={}",
                runId, oldPlain.substring(0, 11), newPlain.substring(0, 11),
                apiKeyService.getByKeyValue(oldPlain) != null,
                apiKeyService.getByKeyValue(newPlain) != null,
                rotated.getTotalTokens(), rotated.getKeyName());

        assertThat(newPlain).as("必须换发一把新的、格式正确的 Key").startsWith("sk-").hasSize(35);
        assertThat(newPlain).as("新 Key 不能和旧的相同").isNotEqualTo(oldPlain);
        assertThat(apiKeyService.getByKeyValue(newPlain)).as("新 Key 必须可用").isNotNull();
        assertThat(apiKeyService.getByKeyValue(oldPlain)).as("旧 Key 必须立即失效").isNull();

        ApiKey fromDb = apiKeyMapper.selectOneById(created.getId());
        assertThat(fromDb.getKeyHash()).as("库里存的是新明文的哈希").isEqualTo(ApiKeyHasher.sha256Hex(newPlain));
        assertThat(fromDb.getKeyValue()).as("新明文同样不落库").isNull();
        assertThat(fromDb.getKeyPrefix()).as("前缀跟着更新").isEqualTo(newPlain.substring(0, 8));
        assertThat(fromDb.getKeySuffix()).as("后缀跟着更新").isEqualTo(newPlain.substring(newPlain.length() - 4));
        assertThat(fromDb.getTotalTokens()).as("用量统计保留在同一行上，便于继续观察这个用途的消耗").isEqualTo(7L);
        assertThat(fromDb.getKeyName()).as("名称保留").isEqualTo(created.getKeyName());
    }

    @Test
    @DisplayName("轮换 · 越权轮换别人的 Key 必须失败，且绝不能改动别人的数据")
    void rotateApiKey_rejectsOthersKey() {
        ApiKey other = insert(newSeed("r2", otherUserId, "active", 0L).build());
        String hashBefore = other.getKeyHash();

        Integer code = codeOf(() -> apiKeyService.rotateApiKey(other.getId(), testUserId));

        log.info("[轮换·越权·{}] 用别人的 id 轮换 → code={}（期望 {}）| 对方哈希是否被改={}",
                runId, code, HttpsCodeEnum.NOT_FOUND_ERROR.getCode(),
                !hashBefore.equals(apiKeyMapper.selectOneById(other.getId()).getKeyHash()));

        assertThat(code).as("轮换必须校验归属").isEqualTo(HttpsCodeEnum.NOT_FOUND_ERROR.getCode());
        assertThat(apiKeyMapper.selectOneById(other.getId()).getKeyHash())
                .as("越权请求绝不能改动对方那一行").isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("轮换 · 已撤销的 Key 轮换后恢复可用（这是「丢了 Key」的主要恢复路径）")
    void rotateApiKey_reactivatesRevokedKey() {
        ApiKey created = apiKeyService.createApiKey(MARKER + runId + "-r3", user(testUserId));
        apiKeyService.revokeApiKey(created.getId(), testUserId);
        assertThat(apiKeyService.getByKeyValue(created.getKeyValue())).as("前置：撤销后不可用").isNull();

        ApiKey rotated = apiKeyService.rotateApiKey(created.getId(), testUserId);

        log.info("[轮换·复活·{}] 撤销后轮换 → status={} 新 Key 可用={}",
                runId, apiKeyMapper.selectOneById(created.getId()).getStatus(),
                apiKeyService.getByKeyValue(rotated.getKeyValue()) != null);

        assertThat(apiKeyMapper.selectOneById(created.getId()).getStatus()).as("轮换后应为 active").isEqualTo("active");
        assertThat(apiKeyService.getByKeyValue(rotated.getKeyValue())).as("新 Key 可用").isNotNull();
    }

    @Test
    @DisplayName("轮换 · 不存在的 id 报 NOT_FOUND")
    void rotateApiKey_notFound() {
        Integer code = codeOf(() -> apiKeyService.rotateApiKey(-1L, testUserId));
        log.info("[轮换·不存在·{}] id=-1 → code={}", runId, code);
        assertThat(code).isEqualTo(HttpsCodeEnum.NOT_FOUND_ERROR.getCode());
    }

    @Test
    @DisplayName("用量 · 记用量不会把已撤销的 Key 复活（回归：原先撤销被静默回滚）")
    void updateUsageStats_doesNotRevertRevoke() {
        ApiKey k = apiKeyService.createApiKey(MARKER + runId + "-s1", user(testUserId));

        // 用 Spring 的 TransactionTemplate 模拟事务
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        // 撤销必须走另一条连接（REQUIRES_NEW）才能提交，否则外层快照看不到「已提交」这个事实
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(tx -> {
            // ① 另一个请求读到了这行（快照 status=active）
            ApiKey snapshot = apiKeyMapper.selectOneById(k.getId());
            assertThat(snapshot.getStatus()).as("前置：快照读到的还是 active").isEqualTo("active");

            // ② 撤销在这中间提交了
            inner.executeWithoutResult(innerTx -> apiKeyService.revokeApiKey(k.getId(), testUserId));

            // ③ 那个请求现在才回来记用量 —— 旧实现会在这里把第 ① 步的整行写回，
            //    status 跟着从 revoked 覆盖回 active
            apiKeyService.updateUsageStats(k.getId(), 5);
        });

        ApiKey after = apiKeyMapper.selectOneById(k.getId());
        ApiKey stillUsable = apiKeyService.getByKeyValue(k.getKeyValue());
        log.info("[用量·撤销不回滚·{}] 记用量后 status={} | 该 Key 还能认证={} | totalTokens={}",
                runId, after.getStatus(), stillUsable != null, after.getTotalTokens());

        // 旧实现逻辑：撤销时 status 变成 revoked，记用量时再把 status 跟着改成 active
        assertThat(after.getStatus()).as("记用量不得改动 status").isEqualTo("revoked");
        assertThat(stillUsable).as("已撤销的 Key 必须保持不可用").isNull();
        // 而它该做的事仍然做到：这笔用量要记上（第 ③ 步不能因为 Key 被撤销就不记账）
        assertThat(after.getTotalTokens()).as("撤销后到达的用量仍应入账").isEqualTo(5L);
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
     *
     * <p>V1002 之后库里只认 {@code keyHash}，所以种子必须同时写哈希与前缀；
     * {@code keyValue} 列已废弃（迁移末尾置空），这里显式写 null 与线上保持一致。
     */
    private ApiKey.ApiKeyBuilder newSeed(String suffix, Long userId, String status, Long totalTokens) {
        LocalDateTime now = LocalDateTime.now();
        String plain = "sk-seed-" + runId + "-" + suffix;
        return ApiKey.builder()
                .userId(userId)
                .keyHash(ApiKeyHasher.sha256Hex(plain))
                .keyPrefix(ApiKeyHasher.prefix(plain))
                .keySuffix(ApiKeyHasher.suffix(plain))
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

    private long countByKeyHash(String keyHash) {
        return countRows("keyHash = ?", keyHash);
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
