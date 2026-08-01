package com.xiyiyun.shop.mvp;

import java.util.Optional;

/**
 * 令牌鉴权出口：mvp 包对外（realtime / 根包过滤器）暴露的唯一鉴权能力。
 *
 * <p>入站适配器（WebSocket 握手拦截器、Admin Token 过滤器）只依赖本接口，
 * 不再依赖 {@link InMemoryShopRepository} 具体实现，从而切断
 * realtime → mvp 具体实现类 与 mvp → realtime 之间的包级循环依赖。
 */
public interface TokenAuthPort {
    Optional<UserItem> findUserByToken(String token);

    Optional<AdminProfile> findAdminByToken(String token);
}
