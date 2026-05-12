package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoodsPlatformPolicyTest {
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

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(mock(OrderRealtimeBroadcaster.class), "admin", "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq", "Admin");
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
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField("goods");
            field.setAccessible(true);
            return (Map<Long, GoodsItem>) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
