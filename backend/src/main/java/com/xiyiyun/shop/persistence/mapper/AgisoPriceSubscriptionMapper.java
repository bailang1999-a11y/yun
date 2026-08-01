package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgisoPriceSubscriptionMapper extends BaseMapper<AgisoPriceSubscriptionEntity> {
    @Update("""
        INSERT INTO agiso_price_subscriptions (
            user_id, platform_user_id, supplier_account_guid, product_no, state,
            last_notified_price, last_price_ver, next_check_at, attempt_count
        ) VALUES (
            #{userId}, #{platformUserId}, #{supplierAccountGuid}, #{productNo}, 'ACTIVE',
            #{currentPrice}, #{priceVer}, #{nextCheckAt}, 0
        )
        ON DUPLICATE KEY UPDATE
            id = LAST_INSERT_ID(id),
            platform_user_id = VALUES(platform_user_id),
            state = 'ACTIVE',
            last_notified_price = VALUES(last_notified_price),
            last_price_ver = GREATEST(last_price_ver, VALUES(last_price_ver)),
            next_check_at = VALUES(next_check_at),
            lease_until = NULL,
            lease_token = NULL,
            attempt_count = 0,
            last_error = NULL,
            last_notified_at = NULL
        """)
    int subscribe(
        @Param("userId") Long userId,
        @Param("platformUserId") String platformUserId,
        @Param("supplierAccountGuid") String supplierAccountGuid,
        @Param("productNo") Long productNo,
        @Param("currentPrice") BigDecimal currentPrice,
        @Param("priceVer") long priceVer,
        @Param("nextCheckAt") OffsetDateTime nextCheckAt
    );

    @Select("""
        SELECT id, user_id, platform_user_id, supplier_account_guid, product_no, state,
               last_notified_price, last_price_ver, next_check_at, lease_until, lease_token, attempt_count,
               last_error, last_notified_at, created_at, updated_at
        FROM agiso_price_subscriptions
        WHERE user_id = #{userId}
          AND supplier_account_guid = #{supplierAccountGuid}
          AND product_no = #{productNo}
        LIMIT 1
        """)
    AgisoPriceSubscriptionEntity selectBySubscription(
        @Param("userId") Long userId,
        @Param("supplierAccountGuid") String supplierAccountGuid,
        @Param("productNo") Long productNo
    );

    @Update("""
        UPDATE agiso_price_subscriptions
        SET state = 'CANCELLED',
            next_check_at = NULL,
            lease_until = NULL,
            lease_token = NULL,
            last_error = NULL
        WHERE user_id = #{userId}
          AND supplier_account_guid = #{supplierAccountGuid}
          AND product_no = #{productNo}
          AND state IN ('ACTIVE', 'CHECKING')
        """)
    int cancel(
        @Param("userId") Long userId,
        @Param("supplierAccountGuid") String supplierAccountGuid,
        @Param("productNo") Long productNo
    );

    @Select("""
        SELECT id, user_id, platform_user_id, supplier_account_guid, product_no, state,
               last_notified_price, last_price_ver, next_check_at, lease_until, lease_token, attempt_count,
               last_error, last_notified_at, created_at, updated_at
        FROM agiso_price_subscriptions
        WHERE (state = 'ACTIVE' AND next_check_at IS NOT NULL AND next_check_at <= #{now})
           OR (state = 'CHECKING' AND lease_until IS NOT NULL AND lease_until <= #{now})
        ORDER BY COALESCE(next_check_at, lease_until, created_at), id
        LIMIT #{limit}
        """)
    List<AgisoPriceSubscriptionEntity> selectDue(
        @Param("now") OffsetDateTime now,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE agiso_price_subscriptions
        SET state = 'CHECKING',
            attempt_count = attempt_count + 1,
            next_check_at = NULL,
            lease_until = #{leaseUntil},
            lease_token = #{leaseToken}
        WHERE id = #{id}
          AND ((state = 'ACTIVE' AND next_check_at IS NOT NULL AND next_check_at <= #{now})
            OR (state = 'CHECKING' AND lease_until IS NOT NULL AND lease_until <= #{now}))
        """)
    int claim(
        @Param("id") Long id,
        @Param("now") OffsetDateTime now,
        @Param("leaseUntil") OffsetDateTime leaseUntil,
        @Param("leaseToken") String leaseToken
    );

    @Update("""
        UPDATE agiso_price_subscriptions
        SET state = 'ACTIVE',
            next_check_at = #{nextCheckAt},
            lease_until = NULL,
            lease_token = NULL,
            attempt_count = 0,
            last_error = NULL
        WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}
        """)
    int markUnchanged(
        @Param("id") Long id,
        @Param("leaseToken") String leaseToken,
        @Param("nextCheckAt") OffsetDateTime nextCheckAt
    );

    @Update("""
        UPDATE agiso_price_subscriptions
        SET state = 'ACTIVE',
            last_notified_price = #{price},
            last_price_ver = #{priceVer},
            last_notified_at = #{notifiedAt},
            next_check_at = #{nextCheckAt},
            lease_until = NULL,
            lease_token = NULL,
            attempt_count = 0,
            last_error = NULL
        WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}
        """)
    int markNotified(
        @Param("id") Long id,
        @Param("leaseToken") String leaseToken,
        @Param("price") BigDecimal price,
        @Param("priceVer") long priceVer,
        @Param("notifiedAt") OffsetDateTime notifiedAt,
        @Param("nextCheckAt") OffsetDateTime nextCheckAt
    );

    @Update("""
        UPDATE agiso_price_subscriptions
        SET state = 'ACTIVE',
            next_check_at = #{nextCheckAt},
            lease_until = NULL,
            lease_token = NULL,
            last_error = #{lastError}
        WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}
        """)
    int retry(
        @Param("id") Long id,
        @Param("leaseToken") String leaseToken,
        @Param("nextCheckAt") OffsetDateTime nextCheckAt,
        @Param("lastError") String lastError
    );
}
