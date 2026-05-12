package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.UserItem;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CategoryRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GoodsRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserRecordMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogPersistenceStoreTest {
    private final CategoryRecordMapper categoryRecordMapper = mock(CategoryRecordMapper.class);
    private final GoodsRecordMapper goodsRecordMapper = mock(GoodsRecordMapper.class);
    private final UserRecordMapper userRecordMapper = mock(UserRecordMapper.class);
    private final CatalogPersistenceStore store = new CatalogPersistenceStore(
        categoryRecordMapper,
        goodsRecordMapper,
        userRecordMapper
    );

    @Test
    void listCategoriesBuildsLevelAndChildrenMetadata() {
        CategoryRecordEntity root = category(1L, null, "会员权益", 10);
        CategoryRecordEntity child = category(11L, 1L, "视频平台", 20);
        when(categoryRecordMapper.selectActiveSnapshots()).thenReturn(List.of(root, child));

        List<CategoryItem> categories = store.listCategories();

        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).level()).isEqualTo(1);
        assertThat(categories.get(0).hasChildren()).isTrue();
        assertThat(categories.get(1).level()).isEqualTo(2);
        assertThat(categories.get(1).hasChildren()).isFalse();
    }

    @Test
    void listGoodsUsesPersistedCategoryName() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        when(categoryRecordMapper.selectActiveSnapshots()).thenReturn(List.of(category(11L, null, "视频平台", 10)));
        GoodsRecordEntity goods = new GoodsRecordEntity();
        goods.setId(100L);
        goods.setCategoryId(11L);
        goods.setName("会员卡");
        goods.setGoodsType("CARD");
        goods.setStatus("ON_SALE");
        goods.setSalePrice(new BigDecimal("9.90"));
        goods.setFaceValue(new BigDecimal("19.90"));
        goods.setStockCount(8);
        goods.setMaxQty(3);
        goods.setCreatedAt(now);
        when(goodsRecordMapper.selectActiveSnapshots()).thenReturn(List.of(goods));

        List<GoodsItem> items = store.listGoods();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).categoryName()).isEqualTo("视频平台");
        assertThat(items.get(0).stock()).isEqualTo(8);
    }

    @Test
    void listUsersMapsPersistedUsers() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        UserRecordEntity user = new UserRecordEntity();
        user.setId(9L);
        user.setMobile("13800000000");
        user.setNickname("用户");
        user.setGroupId(1L);
        user.setBalance(new BigDecimal("88.00"));
        user.setStatus("NORMAL");
        user.setCreatedAt(now);
        when(userRecordMapper.selectActiveSnapshots()).thenReturn(List.of(user));

        List<UserItem> users = store.listUsers();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).id()).isEqualTo(9L);
        assertThat(users.get(0).balance()).isEqualByComparingTo("88.00");
    }

    @Test
    void deleteCategorySoftDeletesRecord() {
        store.deleteCategory(11L);

        verify(categoryRecordMapper).softDelete(11L);
    }

    private CategoryRecordEntity category(Long id, Long parentId, String name, int sort) {
        CategoryRecordEntity category = new CategoryRecordEntity();
        category.setId(id);
        category.setParentId(parentId);
        category.setName(name);
        category.setSortNo(sort);
        category.setStatus("ON_SALE");
        return category;
    }
}
