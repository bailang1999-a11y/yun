package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ItDatabase;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.InMemoryShopRepository;
import com.xiyiyun.shop.persistence.CardCipherService;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类：真实 MySQL（xiyiyun_test）+ 真实 Spring 上下文。
 *
 * <h2>库结构从哪来</h2>
 * 每个测试类执行前（{@code @BeforeAll}，早于 Spring 上下文创建）先 DROP 掉 xiyiyun_test
 * 里所有表与遗留存储过程，再按 {@code 001_schema.sql → 002 → 003 → 004 → 005 → 006} 顺序
 * 重放真实脚本，所以测试跑的是生产结构，不是手写的简化表。
 *
 * <h2>DELIMITER 怎么处理</h2>
 * 005/006 里有 {@code DELIMITER $$} 包裹的存储过程，按分号切分会把过程体切坏。
 * 本来最省事的做法是把整个文件交给 mysql 客户端（{@code scripts/schema-check.mjs} 就是这么做的），
 * 但已实测 backend 容器里<b>没有 mysql 客户端</b>，容器内也没有 docker CLI 去 exec mysql 容器，
 * 而按约束所有测试必须在容器内运行。因此这里走纯 JDBC，
 * 由 {@link com.xiyiyun.shop.it.support.SqlScriptRunner} 实现 DELIMITER 感知的切分：
 * 遇到 {@code DELIMITER x} 就改用 x 作语句终止符，同时正确跳过引号字面量与三种注释。
 * DELIMITER 本身是客户端指令、不发给服务端，所以这样处理与 mysql 客户端等价。
 *
 * <h2>不会静默跳过</h2>
 * 数据库连不上时抛 {@link IllegalStateException}（见 {@link ItDatabase#openConnection()}），
 * 测试计为失败/错误。这里刻意不用 {@code Assumptions.assumeTrue} —— 被静默跳过的测试等于没有测试。
 *
 * <h2>只写 xiyiyun_test</h2>
 * 数据源 URL 由 {@code @DynamicPropertySource} 注入（优先级高于容器传进来的
 * {@code SPRING_DATASOURCE_URL} 环境变量，后者指向 xiyiyun 生产库），
 * 且每次建连都用 {@code SELECT DATABASE()} 复核库名，连错库立即终止。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected InMemoryShopRepository repository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected CardCipherService cardCipherService;

    protected ItFixtures fixtures;

    /**
     * 覆盖容器环境变量 SPRING_DATASOURCE_URL（它指向 xiyiyun 生产库）。
     * {@code @DynamicPropertySource} 注册的属性源优先级高于 OS 环境变量，是唯一可靠的覆盖点。
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> ItDatabase.URL);
        registry.add("spring.datasource.username", () -> ItDatabase.USERNAME);
        registry.add("spring.datasource.password", () -> ItDatabase.PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.data.redis.host", () -> ItDatabase.REDIS_HOST);
        registry.add("xiyiyun.card.encryption-secret", () -> ItDatabase.CARD_ENCRYPTION_SECRET);
        registry.add("xiyiyun.payment.callback-secret", () -> ItDatabase.PAYMENT_CALLBACK_SECRET);
        registry.add("xiyiyun.member-callback.enabled", () -> "false");
    }

    /** 早于 Spring 上下文创建执行：先把库结构摆好，再让持久化 Bean 启动。 */
    @BeforeAll
    static void rebuildSchemaBeforeClass() {
        ItDatabase.rebuildSchema();
    }

    @BeforeEach
    void prepareFixtures() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // 复核 Spring 数据源真的落在测试库上，而不是被环境变量顶成了生产库
            ItDatabase.assertConnectedToTestDatabase(connection);
        }
        fixtures = new ItFixtures(jdbcTemplate, cardCipherService);
        fixtures.truncateBusinessTables();
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
            .as("集成测试必须运行在 xiyiyun_test 库上")
            .isEqualTo(ItDatabase.DATABASE_NAME);
    }
}
