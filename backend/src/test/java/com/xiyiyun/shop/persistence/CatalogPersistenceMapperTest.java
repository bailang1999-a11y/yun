package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.UserItem;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogPersistenceMapperTest {
    private final CatalogPersistenceMapper mapper = new CatalogPersistenceMapper();

    @Test
    void mapsCategorySnapshot() {
        CategoryRecordEntity entity = mapper.toCategoryRecord(new CategoryItem(11L, "视频平台", 1L, 10, true));

        assertThat(entity.getId()).isEqualTo(11L);
        assertThat(entity.getParentId()).isEqualTo(1L);
        assertThat(entity.getStatus()).isEqualTo("ON_SALE");
    }

    @Test
    void mapsGoodsSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        GoodsItem goods = new GoodsItem(
            100L,
            11L,
            "分类",
            "会员卡",
            "会员卡",
            "subtitle",
            "description",
            List.of(),
            "",
            List.of(),
            List.of(),
            List.of(),
            false,
            false,
            GoodsType.CARD,
            "GENERAL",
            new BigDecimal("9.90"),
            new BigDecimal("19.90"),
            3,
            false,
            List.of(),
            "default",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            8,
            0,
            "ON_SALE",
            List.of(),
            now,
            now,
            List.of(),
            List.of(),
            5L
        );

        GoodsRecordEntity entity = mapper.toGoodsRecord(goods);

        assertThat(entity.getId()).isEqualTo(100L);
        assertThat(entity.getGoodsType()).isEqualTo("CARD");
        assertThat(entity.getSalePrice()).isEqualByComparingTo("9.90");
        assertThat(entity.getStockCount()).isEqualTo(8);
    }

    @Test
    void mapsUserSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        UserItem user = new UserItem(
            9L,
            "avatar",
            "13800000000",
            null,
            "用户",
            1L,
            "默认组",
            new BigDecimal("88.00"),
            BigDecimal.ZERO,
            "NORMAL",
            now,
            now.plusMinutes(1),
            "MOBILE",
            null,
            null,
            null,
            "VERIFIED"
        );

        UserRecordEntity entity = mapper.toUserRecord(user);

        assertThat(entity.getId()).isEqualTo(9L);
        assertThat(entity.getMobile()).isEqualTo("13800000000");
        assertThat(entity.getBalance()).isEqualByComparingTo("88.00");
        assertThat(entity.getLastLoginAt()).isEqualTo(now.plusMinutes(1));
    }
}
