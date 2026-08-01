package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoodsPlatformPolicyTest {
    @Test
    void listGoodsMatchesExactSystemGoodsId() {
        InMemoryShopRepository repository = newRepository();

        assertThat(repository.listGoods(null, " 10001 ", "h5", false))
            .extracting(GoodsItem::id)
            .containsExactly(10001L);
    }

    @Test
    void listGoodsDoesNotMatchPartialOrUnknownSystemGoodsId() {
        InMemoryShopRepository repository = newRepository();

        assertThat(repository.listGoods(null, "1000", "h5", false)).isEmpty();
        assertThat(repository.listGoods(null, "999999", "h5", false)).isEmpty();
    }

    @Test
    void numericSearchDoesNotFallBackToGoodsText() {
        InMemoryShopRepository repository = newRepository();

        assertThat(repository.listGoods(null, "60", "h5", false)).isEmpty();
    }

    @Test
    void exactSystemGoodsIdSearchRespectsPlatformPolicy() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("h5", "web"), List.of("web")));

        assertThat(repository.listGoods(null, "10001", "web", false)).isEmpty();
        assertThat(repository.listGoods(null, "10001", "h5", false))
            .extracting(GoodsItem::id)
            .containsExactly(10001L);
    }

    @Test
    void listGoodsExcludesForbiddenPlatform() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("h5", "web"), List.of("web")));

        List<GoodsItem> webGoods = repository.listGoods(null, "", "web", false);
        List<GoodsItem> h5Goods = repository.listGoods(null, "", "h5", false);

        assertThat(webGoods).noneMatch(item -> item.id().equals(10001L));
        assertThat(h5Goods).anyMatch(item -> item.id().equals(10001L));
    }

    @Test
    void createOrderRejectsForbiddenTerminal() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("h5", "web"), List.of("web")));

        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(10001L, 1, "", "web forbidden", "platform-1", "web"),
            90001L,
            "",
            "h5"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("该商品未开放当前端购买。");
    }

    @Test
    void createMemberOrderRequiresApiPlatform() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("h5", "web"), List.of()));

        assertThatThrownBy(() -> repository.createMemberOrder(
            new CreateOrderRequest(10001L, 1, "", "api forbidden", "platform-2"),
            90002L
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("该商品未开放当前端购买。");
    }

    @Test
    void goodsDetailRejectsForbiddenTerminal() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("h5", "web"), List.of("web")));

        assertThat(repository.findGoods(10001L, 1L, false, "web")).isEmpty();
        assertThat(repository.findGoods(10001L, 1L, false, "h5")).isPresent();
    }

    @Test
    void marketplaceChannelLabelsDoNotHideStorefrontGoods() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of("taobao", "pdd"), List.of("douyin")));

        List<GoodsItem> webGoods = repository.listGoods(null, "", "web", false);
        List<GoodsItem> h5Goods = repository.listGoods(null, "", "h5", false);

        assertThat(webGoods).anyMatch(item -> item.id().equals(10001L));
        assertThat(h5Goods).anyMatch(item -> item.id().equals(10001L));
    }

    @Test
    void emptyAvailablePlatformsBehaveAsNoStorefrontRestriction() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10001L);
        goods(repository).put(10001L, withPlatforms(original, List.of(), List.of()));

        List<GoodsItem> webGoods = repository.listGoods(null, "", "web", false);
        List<GoodsItem> h5Goods = repository.listGoods(null, "", "h5", false);

        assertThat(webGoods).anyMatch(item -> item.id().equals(10001L));
        assertThat(h5Goods).anyMatch(item -> item.id().equals(10001L));
    }

    @Test
    void productMonitorOnlyIncludesRealtimeSuppliers() {
        InMemoryShopRepository repository = newRepository();
        OffsetDateTime now = OffsetDateTime.now();
        suppliers(repository).put(20003L, supplier(20003L, "实时货源", "KASUSHOU_2", "https://open.kasushou.cn"));
        suppliers(repository).put(20004L, supplier(20004L, "福禄", "FULU", "https://fulu.example.com"));
        suppliers(repository).put(20005L, supplier(20005L, "占位货源", "JINGZHAO", "https://api.example.com/{placeholder}"));
        goodsChannels(repository).put(30003L, new GoodsChannelItem(30003L, 10001L, 20003L, "实时货源", "REMOTE-1", 10, 30, "ENABLED", now));
        goodsChannels(repository).put(30004L, new GoodsChannelItem(30004L, 10001L, 20004L, "福禄", "FULU-1", 20, 30, "ENABLED", now));
        goodsChannels(repository).put(30005L, new GoodsChannelItem(30005L, 10001L, 20005L, "占位货源", "PLACEHOLDER-1", 30, 30, "ENABLED", now));

        assertThat(repository.dueProductMonitorChannelIds(now))
            .contains(30003L)
            .doesNotContain(30004L, 30005L);
        assertThat(repository.productMonitorOverview(1, 20).items())
            .extracting(ProductMonitorItem::channelId)
            .contains(30003L)
            .doesNotContain(30004L, 30005L);
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(mock(OrderRealtimeBroadcaster.class), "admin", "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq", "Admin");
    }

    private static SupplierItem supplier(Long id, String name, String platformType, String baseUrl) {
        return new SupplierItem(
            id,
            name,
            platformType,
            baseUrl,
            "app-key",
            "app****key",
            "",
            "",
            "api-key",
            "api****key",
            "",
            30,
            BigDecimal.ZERO,
            "ENABLED",
            "",
            OffsetDateTime.now()
        );
    }

    private static GoodsItem withPlatforms(GoodsItem item, List<String> availablePlatforms, List<String> forbiddenPlatforms) {
        return new GoodsItem(
            item.id(),
            item.categoryId(),
            item.categoryName(),
            item.goodsName(),
            item.name(),
            item.subTitle(),
            item.description(),
            item.benefitDurations(),
            item.benefitType(),
            item.benefitBrand(),
            item.priceLimited(),
            item.priceLimitText(),
            item.coverUrl(),
            item.detailImages(),
            item.detailBlocks(),
            item.integrations(),
            item.pollingEnabled(),
            item.monitoringEnabled(),
            item.type(),
            item.platform(),
            item.price(),
            item.originalPrice(),
            item.maxBuy(),
            item.requireRechargeAccount(),
            item.accountTypes(),
            item.priceTemplateId(),
            item.priceMode(),
            item.priceCoefficient(),
            item.priceFixedAdd(),
            item.stock(),
            item.sales(),
            item.status(),
            item.tags(),
            item.createdAt(),
            item.updatedAt(),
            availablePlatforms,
            forbiddenPlatforms,
            item.cardKindId()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, GoodsItem> goods(InMemoryShopRepository repository) {
        return catalogField(repository, "goods");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, SupplierItem> suppliers(InMemoryShopRepository repository) {
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField("suppliers");
            field.setAccessible(true);
            return (Map<Long, SupplierItem>) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, GoodsChannelItem> goodsChannels(InMemoryShopRepository repository) {
        return catalogField(repository, "goodsChannels");
    }

    /**
     * 批次7B / 任务B：goods / goodsChannels 已随商品域搬到 {@link CatalogService}。
     * 这里只机械改反射目标（仓储字段 → catalogService 字段），断言逐字未变。
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> catalogField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("catalogService");
            holder.setAccessible(true);
            Object catalog = holder.get(repository);
            Field field = catalog.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(catalog);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
