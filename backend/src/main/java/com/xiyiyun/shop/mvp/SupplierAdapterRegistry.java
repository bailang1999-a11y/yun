package com.xiyiyun.shop.mvp;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 按 platformType 查找 {@link SupplierAdapter}。
 *
 * <p>这是替换 6 处分发链的落点：仓储层不再写
 * {@code if (isKasushouSupplier(x)) ... else if (isFuluSupplier(x)) ...}，
 * 而是 {@code registry.require(supplier).submitOrder(...)}。</p>
 */
@Component
public class SupplierAdapterRegistry {
    private final Map<SupplierPlatform, SupplierAdapter> adapters = new EnumMap<>(SupplierPlatform.class);
    private final List<SupplierAdapter> ordered;

    /** 默认 7 家。手工 new 仓储的测试与非 Spring 场景走这里。 */
    static SupplierAdapterRegistry defaultRegistry() {
        return new SupplierAdapterRegistry(List.of(
            new KasushouSupplierAdapter(),
            new KakayunSupplierAdapter(),
            new FuluSupplierAdapter(),
            new FengzhushouSupplierAdapter(),
            new ChengquanSupplierAdapter(),
            new FanchenSupplierAdapter(),
            new JingzhaoSupplierAdapter()
        ));
    }

    public SupplierAdapterRegistry(List<SupplierAdapter> adapters) {
        for (SupplierAdapter adapter : adapters) {
            SupplierAdapter previous = this.adapters.put(adapter.platform(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                    "duplicate SupplierAdapter for platform " + adapter.platform()
                        + ": " + previous.getClass().getName() + " vs " + adapter.getClass().getName()
                );
            }
        }
        this.ordered = List.copyOf(adapters);
    }

    public Optional<SupplierAdapter> find(String platformType) {
        return SupplierPlatform.fromPlatformType(platformType)
            .map(adapters::get);
    }

    public Optional<SupplierAdapter> find(SupplierItem item) {
        return item == null ? Optional.empty() : find(item.platformType());
    }

    public SupplierAdapter require(SupplierItem item) {
        return find(item).orElseThrow(() -> new IllegalArgumentException("supplier platformType is not supported"));
    }

    public SupplierAdapter require(SupplierPlatform platform) {
        SupplierAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            throw new IllegalStateException("no SupplierAdapter registered for " + platform);
        }
        return adapter;
    }

    /** 是否有任何适配器认领该 platformType，即「是否为已对接的 API 供应商」。 */
    public boolean isKnownPlatform(String platformType) {
        return find(platformType).isPresent();
    }

    public List<SupplierAdapter> all() {
        return ordered;
    }
}
