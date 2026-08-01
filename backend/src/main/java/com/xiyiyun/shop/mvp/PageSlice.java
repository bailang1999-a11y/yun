package com.xiyiyun.shop.mvp;

import java.util.List;

/**
 * 批次8C：一页数据 + 全量总数。
 *
 * <p>用来替换「先把整张表读进内存、再在控制层 {@code subList} 切页」的假分页。
 * 仓储层返回本类型，控制层只负责把它包成对外的 {@link PageResult}，
 * 这样 {@code total} 就来自 SQL 的 {@code COUNT(*)}，而不是内存列表的 {@code size()}。
 *
 * @param items 当前页的数据，顺序即最终展示顺序
 * @param total 满足筛选条件的总行数（不受 limit/offset 影响）
 */
public record PageSlice<T>(List<T> items, long total) {

    public PageSlice {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageSlice<T> empty() {
        return new PageSlice<>(List.of(), 0L);
    }

    /**
     * 内存兜底路径用：对一份已经筛选、排序完成的整表列表做切页。
     *
     * <p>仅在持久层不可用（单元测试、DB 读失败降级）时走这里。
     * 此时数据本来就只在内存 Map 里，规模有界，切页不构成风险。
     */
    public static <T> PageSlice<T> of(List<T> all, int limit, long offset) {
        if (all == null || all.isEmpty()) {
            return empty();
        }
        int total = all.size();
        int from = (int) Math.min(Math.max(offset, 0L), total);
        int to = (int) Math.min((long) from + Math.max(limit, 0), total);
        return new PageSlice<>(all.subList(from, to), total);
    }
}
