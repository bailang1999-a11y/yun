package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.PageSlice;
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
    public void saveCategorySnapshots(List<CategoryItem> categories) {
        categories.forEach(category -> categoryRecordMapper.upsertSnapshot(persistenceMapper.toCategoryRecord(category)));
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

    @Transactional
    public void deleteGoods(Long id) {
        goodsRecordMapper.hardDelete(id);
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

    /**
     * 批次8C：会员分页，{@code LIMIT/OFFSET} 与 {@code COUNT(*)} 都下推到 SQL。
     *
     * <p>count 与取数是两条独立 SQL，必须共用同一个 WHERE（这里是
     * {@code deleted_at IS NULL}）并跑在同一个只读事务里：否则期间的注销/注册会让
     * total 与本页数据来自不同时刻，出现「总数 41 但翻到第 5 页是空的」。
     *
     * <p>与 {@link #listUsers} 不同，这里<b>不</b>再做 Java 侧 {@code sorted(by id)}：
     * SQL 的 {@code ORDER BY id} 已经决定了全局顺序，切页后在 Java 里重排只能打乱
     * 页与页之间的关系（每页各自有序、整体乱序）。分页场景下排序必须由 SQL 独占。
     */
    @Transactional(readOnly = true)
    public PageSlice<UserItem> pageUsers(int limit, long offset) {
        long total = userRecordMapper.countSnapshots();
        if (total <= offset) {
            // 越界页不再查数据，但 total 如实返回，前端才能把页码收回有效范围。
            return new PageSlice<>(List.of(), total);
        }
        List<UserItem> items = userRecordMapper.selectActiveSnapshotPage(limit, offset).stream()
            .map(persistenceMapper::toUserItem)
            .toList();
        return new PageSlice<>(items, total);
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
