package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.UserItem;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CategoryRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GoodsRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserRecordMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogPersistenceStore {
    private final CategoryRecordMapper categoryRecordMapper;
    private final GoodsRecordMapper goodsRecordMapper;
    private final UserRecordMapper userRecordMapper;
    private final CatalogPersistenceMapper persistenceMapper = new CatalogPersistenceMapper();

    public CatalogPersistenceStore(
        CategoryRecordMapper categoryRecordMapper,
        GoodsRecordMapper goodsRecordMapper,
        UserRecordMapper userRecordMapper
    ) {
        this.categoryRecordMapper = categoryRecordMapper;
        this.goodsRecordMapper = goodsRecordMapper;
        this.userRecordMapper = userRecordMapper;
    }

    @Transactional
    public CategoryRecordEntity saveCategorySnapshot(CategoryItem category) {
        CategoryRecordEntity entity = persistenceMapper.toCategoryRecord(category);
        categoryRecordMapper.upsertSnapshot(entity);
        return entity;
    }

    @Transactional
    public GoodsRecordEntity saveGoodsSnapshot(GoodsItem goods) {
        GoodsRecordEntity entity = persistenceMapper.toGoodsRecord(goods);
        goodsRecordMapper.upsertSnapshot(entity);
        return entity;
    }

    @Transactional
    public UserRecordEntity saveUserSnapshot(UserItem user) {
        UserRecordEntity entity = persistenceMapper.toUserRecord(user);
        userRecordMapper.upsertSnapshot(entity);
        return entity;
    }

    @Transactional
    public void deleteCategory(Long id) {
        categoryRecordMapper.softDelete(id);
    }

    @Transactional(readOnly = true)
    public List<CategoryItem> listCategories() {
        List<CategoryRecordEntity> records = categoryRecordMapper.selectActiveSnapshots();
        Map<Long, CategoryRecordEntity> byId = records.stream()
            .collect(Collectors.toMap(CategoryRecordEntity::getId, Function.identity(), (left, right) -> left));
        return records.stream()
            .map(record -> persistenceMapper.toCategoryItem(
                record,
                categoryLevel(record, byId),
                records.stream().anyMatch(candidate -> record.getId().equals(candidate.getParentId()))
            ))
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<GoodsItem> listGoods() {
        List<CategoryItem> categories = listCategories();
        Map<Long, String> categoryNames = categories.stream()
            .collect(Collectors.toMap(CategoryItem::id, CategoryItem::name, (left, right) -> left));
        return goodsRecordMapper.selectActiveSnapshots().stream()
            .map(record -> persistenceMapper.toGoodsItem(record, categoryNames.getOrDefault(record.getCategoryId(), "未分类")))
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<UserItem> listUsers() {
        return userRecordMapper.selectActiveSnapshots().stream()
            .map(persistenceMapper::toUserItem)
            .sorted(Comparator.comparing(UserItem::id))
            .toList();
    }

    private int categoryLevel(CategoryRecordEntity record, Map<Long, CategoryRecordEntity> byId) {
        int level = 1;
        Long parentId = record.getParentId();
        List<Long> visited = new ArrayList<>();
        while (parentId != null && byId.containsKey(parentId) && !visited.contains(parentId)) {
            visited.add(parentId);
            level++;
            parentId = byId.get(parentId).getParentId();
        }
        return level;
    }
}
