package com.xiyiyun.shop.mvp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 上游供应商平台标识。
 *
 * <p>把原先散落在 InMemoryShopRepository 里的 7 组 {@code isXxxPlatform(String)} 判定
 * 收敛到一处：每个枚举常量自带「归一化别名集合」+「原样中文名集合」，
 * 匹配规则与被替换的旧方法逐条等价（含 {@code -} 转 {@code _} 的归一化）。</p>
 */
public enum SupplierPlatform {
    KASUSHOU(
        "kasushou",
        "卡速售",
        Set.of("kasushou_2", "kasushou", "kasu"),
        Set.of()
    ),
    KAKAYUN(
        "kakayun",
        "咔咔云",
        Set.of("kakayun", "kaka_yun", "kky"),
        Set.of("卡卡云")
    ),
    FULU(
        "fulu",
        "福禄新平台",
        Set.of("fulu", "fulu_new", "fulu_new_platform"),
        Set.of("福禄", "福禄新平台")
    ),
    FENGZHUSHOU(
        "fengzhushou",
        "蜂助手",
        Set.of("fengzhushou", "feng_zhushou", "fzs", "phone580"),
        Set.of("蜂助手", "蜂助手直充")
    ),
    CHENGQUAN(
        "chengquan",
        "鼎信橙券",
        Set.of("chengquan", "dx_chengquan", "dingxin_chengquan"),
        Set.of("橙券", "鼎信橙券")
    ),
    FANCHEN(
        "fanchen",
        "浙江梵尘",
        Set.of("fanchen_rj", "fanchen", "zhejiang_fanchen"),
        Set.of("梵尘瑞景", "浙江梵尘")
    ),
    JINGZHAO(
        "jingzhao",
        "京兆云",
        Set.of("jingzhao", "jingzhao_yun", "xhygo"),
        Set.of("京兆", "京兆云")
    );

    private final String code;
    private final String displayName;
    private final Set<String> normalizedAliases;
    private final Set<String> rawAliases;

    SupplierPlatform(String code, String displayName, Set<String> normalizedAliases, Set<String> rawAliases) {
        this.code = code;
        this.displayName = displayName;
        this.normalizedAliases = normalizedAliases;
        this.rawAliases = rawAliases;
    }

    public String code() {
        return code;
    }

    /** 面向运营的中文平台名，替代原 {@code platformLabelForManualSupplier} 的硬编码分支。 */
    public String displayName() {
        return displayName;
    }

    public boolean matches(String platformType) {
        if (platformType == null) {
            return false;
        }
        String raw = platformType.trim();
        if (!rawAliases.isEmpty() && rawAliases.contains(raw)) {
            return true;
        }
        return normalizedAliases.contains(normalize(raw));
    }

    public static Optional<SupplierPlatform> fromPlatformType(String platformType) {
        if (platformType == null || platformType.isBlank()) {
            return Optional.empty();
        }
        for (SupplierPlatform platform : values()) {
            if (platform.matches(platformType)) {
                return Optional.of(platform);
            }
        }
        return Optional.empty();
    }

    public static Optional<SupplierPlatform> of(SupplierItem item) {
        return item == null ? Optional.empty() : fromPlatformType(item.platformType());
    }

    public static List<SupplierPlatform> all() {
        return List.of(values());
    }

    /**
     * 与旧 {@code normalize(platformType).replace("-", "_")} 完全一致：
     * 去空白 + 转小写 + 连字符转下划线。
     */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
