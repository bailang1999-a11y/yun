package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.AbstractIntegrationTest;
import com.xiyiyun.shop.persistence.AuditPersistenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 审计日志 id 计数器的重启续号。
 *
 * <h2>回归的是什么线上故障</h2>
 * 三张审计表的 {@code insertSnapshot} 都显式写入 id，id 由 {@link AuditService} 里的
 * {@code AtomicLong} 分配。计数器原先每次重启都从 1 开始，新日志撞上库里已有的主键，
 * {@code DuplicateKeyException} 被镜像失败分支吞掉，调用方拿不到任何异常 ——
 * 线上表现为 {@code open_api_logs} 从某一刻起停止写入，且要等计数器一路撞过历史最大 id
 * 才自行恢复，这段窗口的日志全部静默丢失。
 *
 * <h2>为什么必须用真实 MySQL</h2>
 * 故障的判定点是主键冲突，只有真库会抛。用 mock 持久层测不出来：
 * 内存 Map 的 {@code put} 覆盖旧值，永远不会冲突。
 *
 * <p>本类刻意放在 {@code com.xiyiyun.shop.mvp} 包下：{@code AuditService} 的构造函数与
 * {@code appendXxx} 都是包级私有，跨包无法直接构造与调用。
 */
class AuditLogIdSeedingIT extends AbstractIntegrationTest {
    @Autowired
    private AuditPersistenceStore auditPersistenceStore;

    @Test
    void restartedAuditServiceContinuesIdsFromDatabaseMaxInsteadOfCollidingFromOne() {
        // 造出「库里已有历史日志」的局面：id 刻意留空档，验证续号取的是 MAX 而非行数。
        jdbcTemplate.update(
            "INSERT INTO open_api_logs (id, user_id, app_key, path, status, message, created_at) "
                + "VALUES (5000, 90001, 'member_90001', '/dockapiv3/user/info', 'SUCCESS', 'ok', NOW(3))"
        );
        jdbcTemplate.update(
            "INSERT INTO admin_operation_logs (id, admin_name, action, resource_type, resource_id, created_at) "
                + "VALUES (3000, 'system', 'SEED', 'SEED', '1', NOW(3))"
        );

        // 新建实例即模拟进程重启：构造函数负责把计数器续到 MAX(id) + 1。
        AuditService restarted = new AuditService(auditPersistenceStore);
        restarted.appendOpenApiLog(90001L, "member_90001", "/dockapiv3/order/create", "SUCCESS", "ok");
        restarted.appendOperation("RESTART_PROBE", "OPEN_API_LOG", "1", "seeded");

        assertThat(nextId("open_api_logs", 5000L))
            .as("开放接口日志应续到 MAX(id)+1，而不是从 1 开始撞主键")
            .isEqualTo(5001L);
        assertThat(nextId("admin_operation_logs", 3000L))
            .as("操作日志应续到 MAX(id)+1")
            .isEqualTo(3001L);

        // 冲突被静默吞掉时行数不会涨，所以行数是「有没有真的写进去」的直接证据。
        assertThat(count("open_api_logs")).isEqualTo(2L);
        assertThat(count("admin_operation_logs")).isEqualTo(2L);

        // 镜像失败会记一条 PERSISTENCE_MIRROR_FAILED；它不出现才说明插入真的成功了。
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_operation_logs WHERE action = 'PERSISTENCE_MIRROR_FAILED'", Long.class
        )).isZero();
    }

    private long nextId(String table, long seededId) {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(MIN(id), 0) FROM " + table + " WHERE id > ?", Long.class, seededId
        );
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
