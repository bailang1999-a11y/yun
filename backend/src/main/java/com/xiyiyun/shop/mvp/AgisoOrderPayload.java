package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class AgisoOrderPayload {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<OrderStatus> FAILURE_STATUSES = Set.of(
        OrderStatus.FAILED, OrderStatus.REFUNDED, OrderStatus.CANCELLED, OrderStatus.CLOSED
    );

    private AgisoOrderPayload() {
    }

    static Map<String, Object> from(OrderItem order, String appSecret) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", clean(order.requestId()));
        result.put("outTradeNo", clean(order.orderNo()));
        result.put("orderStatus", status(order));
        result.put("failCode", FAILURE_STATUSES.contains(order.status()) ? 1 : 0);
        result.put("failReason", FAILURE_STATUSES.contains(order.status()) ? clean(order.deliveryMessage()) : "");
        result.put("orderCost", money(order.payAmount()));
        result.put("cards", order.goodsType() == GoodsType.CARD && order.status() == OrderStatus.DELIVERED
            ? encryptedCards(order, appSecret)
            : "");
        return result;
    }

    static boolean callbackReady(OrderItem order) {
        return order != null && (order.status() == OrderStatus.DELIVERED || FAILURE_STATUSES.contains(order.status()));
    }

    static int status(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 20;
        if (FAILURE_STATUSES.contains(order.status())) return 30;
        return 10;
    }

    private static String encryptedCards(OrderItem order, String appSecret) {
        List<Map<String, String>> cards = new ArrayList<>();
        List<String> items = order.deliveryItems() == null ? List.of() : order.deliveryItems();
        for (int index = 0; index < items.size(); index++) {
            String item = clean(items.get(index));
            String[] parts = item.split("\\|", 2);
            Map<String, String> card = new LinkedHashMap<>();
            card.put("cardNo", parts.length == 2 ? parts[0] : String.valueOf(index + 1));
            card.put("cardPwd", parts.length == 2 ? parts[1] : item);
            card.put("expireTime", "");
            cards.add(card);
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(cards);
            byte[] key = appSecret == null ? new byte[0] : appSecret.getBytes(StandardCharsets.UTF_8);
            if (key.length != 32) {
                throw new IllegalStateException("agiso app secret must be 32 UTF-8 bytes for card encryption");
            }
            // 91 requires this legacy wire format; card storage continues to use authenticated encryption.
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("card serialization failed", ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("card encryption failed", ex);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
