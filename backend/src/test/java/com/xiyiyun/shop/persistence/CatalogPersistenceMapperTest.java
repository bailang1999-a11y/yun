package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsDetailBlock;
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
            "VIP",
            "腾讯视频",
            true,
            "限10元",
            "/cover.png",
            List.of("/detail.png"),
            List.of(new GoodsDetailBlock("image", "/detail.png", "")),
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
        assertThat(entity.getDeliveryTemplate()).contains("/cover.png", "/detail.png");
        assertThat(entity.getDeliveryTemplate()).contains("\"priceLimited\":true");
        assertThat(entity.getDeliveryTemplate()).contains("\"priceLimitText\":\"限10元\"");

        GoodsItem restored = mapper.toGoodsItem(entity, "分类");
        assertThat(restored.coverUrl()).isEqualTo("/cover.png");
        assertThat(restored.detailImages()).containsExactly("/detail.png");
        assertThat(restored.detailBlocks()).hasSize(1);
        assertThat(restored.priceLimited()).isTrue();
        assertThat(restored.priceLimitText()).isEqualTo("限10元");
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
            "VERIFIED",
            null
        );

        UserRecordEntity entity = mapper.toUserRecord(user);

        assertThat(entity.getId()).isEqualTo(9L);
        assertThat(entity.getMobile()).isEqualTo("13800000000");
        assertThat(entity.getBalance()).isEqualByComparingTo("88.00");
        assertThat(entity.getLastLoginAt()).isEqualTo(now.plusMinutes(1));
    }

    /**
     * 手机号注册的会员 email 为空串。若原样落库，空串会占住 uk_users_email 唯一键，
     * 第二个手机注册会员就会撞键并触发 upsert 覆盖掉前者的资料与余额
     * （生产已复现：整张 users 表只剩一行）。因此必须落 NULL。
     */
    @Test
    void writesNullInsteadOfBlankEmailForMobileUser() {
        UserRecordEntity entity = mapper.toUserRecord(userWith("15200000001", ""));

        assertThat(entity.getMobile()).isEqualTo("15200000001");
        assertThat(entity.getEmail()).isNull();
    }

    /** 邮箱注册的会员 mobile 为空串，同理会撞 uk_users_mobile。 */
    @Test
    void writesNullInsteadOfBlankMobileForEmailUser() {
        UserRecordEntity entity = mapper.toUserRecord(userWith("", "buyer@example.com"));

        assertThat(entity.getMobile()).isNull();
        assertThat(entity.getEmail()).isEqualTo("buyer@example.com");
    }

    /** 只含空白字符也算空，否则 " " 一样会占住唯一键。 */
    @Test
    void treatsWhitespaceOnlyContactAsNull() {
        UserRecordEntity entity = mapper.toUserRecord(userWith("  ", "\t"));

        assertThat(entity.getMobile()).isNull();
        assertThat(entity.getEmail()).isNull();
    }

    private static UserItem userWith(String mobile, String email) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        return new UserItem(
            90005L, "", mobile, email, "买家", 1L, "默认组",
            BigDecimal.ZERO, BigDecimal.ZERO, "NORMAL", now, null,
            "NONE", "", "", "", "UNVERIFIED", null
        );
    }
}
