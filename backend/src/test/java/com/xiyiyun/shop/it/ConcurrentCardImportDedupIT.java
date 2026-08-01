package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CardImportRequest;
import com.xiyiyun.shop.mvp.CardImportResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发用例 6：多线程同时导入含重复卡密的批次。
 *
 * <p><b>预期红灯，暴露缺陷：导入去重只看内存，唯一索引冲突被 ON DUPLICATE KEY UPDATE 吞掉，
 * 导入结果的成功数与真实入库行数不符。</b>
 * <ul>
 *   <li>{@code importCards} 的去重集合来自内存 {@code cards} map；而持久化模式下
 *       构造函数跳过种子、内存 map 起始为空，且导入后写入的是 DB，
 *       所以<b>跨批次/跨线程的重复完全检测不到</b>；</li>
 *   <li>落库走 {@code CardRecordMapper.upsertImportedCard}，SQL 是
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE card_preview=VALUES(...), status=VALUES(...)}。
 *       撞上唯一索引 {@code uk_cards_goods_hash} 时退化成 UPDATE，不新增行、也不报错，
 *       调用方却照样 {@code successCount++}；</li>
 *   <li>更糟的是重复卡密被 UPDATE 时会把 {@code status} 重置回 UNSOLD ——
 *       已售出的卡可能因为一次重复导入而变回可售。</li>
 * </ul>
 * 断言核心：唯一索引必须真正生效（同一卡密只有 1 行），且
 * 「各线程 successCount 之和」必须等于「实际新增行数」。
 */
class ConcurrentCardImportDedupIT extends AbstractIntegrationTest {

    private static final int THREADS = 8;
    /** 每个批次都包含这 5 张卡，且批次内部自身还带 1 张重复 → 跨线程必然撞车。 */
    private static final List<String> BATCH = List.of(
        "IT-DUP-CARD-0001",
        "IT-DUP-CARD-0002",
        "IT-DUP-CARD-0003",
        "IT-DUP-CARD-0002",
        "IT-DUP-CARD-0004");
    private static final int DISTINCT_CARDS = 4;

    @Test
    @DisplayName("多线程导入重复批次：唯一索引生效，成功数与实际入库行数一致")
    void duplicateImportsMustNotCreateDuplicateRows() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("100.0000"));
        fixtures.insertGoods("CARD", 0, new BigDecimal("3.0000"), null);

        List<ConcurrentRunner.Outcome<CardImportResult>> outcomes = ConcurrentRunner.runAll(THREADS,
            index -> () -> repository.importCards(ItFixtures.GOODS_ID,
                new CardImportRequest(new ArrayList<>(BATCH), null)));

        assertThat(ConcurrentRunner.countSucceeded(outcomes))
            .as("导入调用本身不应抛异常；失败原因=%s",
                ConcurrentRunner.failureMessages(outcomes).stream().distinct().limit(3).toList())
            .isEqualTo(THREADS);

        int reportedSuccess = outcomes.stream()
            .filter(ConcurrentRunner.Outcome::succeeded)
            .mapToInt(outcome -> outcome.value().successCount())
            .sum();
        int reportedDuplicate = outcomes.stream()
            .filter(ConcurrentRunner.Outcome::succeeded)
            .mapToInt(outcome -> outcome.value().duplicateCount())
            .sum();

        int actualRows = fixtures.countCards();
        int distinctHashes = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT card_hash) FROM cards", Integer.class);

        // 1) 唯一索引必须真正生效：同一 (goods_id, card_hash) 只能一行
        List<Map<String, Object>> duplicated = fixtures.query("""
            SELECT card_hash, COUNT(*) AS c FROM cards
            GROUP BY goods_id, card_hash HAVING COUNT(*) > 1
            """);
        assertThat(duplicated)
            .as("uk_cards_goods_hash 必须阻止同一卡密插入两次，实际重复组=%s", duplicated)
            .isEmpty();

        // 2) 库里最终只应有 DISTINCT_CARDS 张卡
        assertThat(actualRows)
            .as("%d 个线程各导入同一批 %d 行（去重后 %d 张），库里最终只应有 %d 行，实际 %d 行",
                THREADS, BATCH.size(), DISTINCT_CARDS, DISTINCT_CARDS, actualRows)
            .isEqualTo(DISTINCT_CARDS);
        assertThat(distinctHashes).isEqualTo(DISTINCT_CARDS);

        // 3) 导入结果必须诚实：所有线程报告的成功数之和 == 实际新增行数
        assertThat(reportedSuccess)
            .as("""
                各线程 successCount 之和必须等于实际入库行数。
                报告成功=%d，报告重复=%d，实际入库=%d 行。
                差值来自 upsertImportedCard 的 ON DUPLICATE KEY UPDATE：撞唯一键时退化为 UPDATE，
                不新增行也不报错，但调用方仍然计入 successCount，导入结果对运营是失真的。""",
                reportedSuccess, reportedDuplicate, actualRows)
            .isEqualTo(actualRows);

        // 4) 每个线程报告的 总数 = 成功 + 重复 + 失败行，账要平
        for (ConcurrentRunner.Outcome<CardImportResult> outcome : outcomes) {
            CardImportResult result = outcome.value();
            assertThat(result.successCount() + result.duplicateCount() + result.failedLines().size())
                .as("线程 %d 的导入结果账不平：total=%d success=%d duplicate=%d failed=%d",
                    outcome.index(), result.importTotal(), result.successCount(),
                    result.duplicateCount(), result.failedLines().size())
                .isEqualTo(result.importTotal());
        }

        // 5) 商品库存必须与真实可售卡数一致
        assertThat(fixtures.goodsStock(ItFixtures.GOODS_ID))
            .as("商品库存应等于 UNSOLD 卡数 %d", fixtures.countCardsByStatus("UNSOLD"))
            .isEqualTo(fixtures.countCardsByStatus("UNSOLD"));
    }
}
