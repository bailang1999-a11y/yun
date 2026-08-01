package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgisoCallbackTaskMapper extends BaseMapper<AgisoCallbackTaskEntity> {
    @Update("""
        INSERT INTO agiso_callback_tasks (
            user_id, request_id, callback_url, goods_type, state, attempt_count, next_attempt_at
        ) VALUES (
            #{userId}, #{requestId}, #{callbackUrl}, #{goodsType}, 'PENDING', 0, #{nextAttemptAt}
        )
        ON DUPLICATE KEY UPDATE
            id = LAST_INSERT_ID(id),
            callback_url = IF(state = 'DEAD' AND order_no IS NULL, VALUES(callback_url), callback_url),
            goods_type = IF(state = 'DEAD' AND order_no IS NULL, VALUES(goods_type), goods_type),
            attempt_count = IF(state = 'DEAD' AND order_no IS NULL, 0, attempt_count),
            next_attempt_at = IF(state = 'DEAD' AND order_no IS NULL, VALUES(next_attempt_at), next_attempt_at),
            lease_until = IF(state = 'DEAD' AND order_no IS NULL, NULL, lease_until),
            last_error = IF(state = 'DEAD' AND order_no IS NULL, NULL, last_error),
            state = IF(state = 'DEAD' AND order_no IS NULL, 'PENDING', state)
        """)
    int registerPending(
        @Param("userId") Long userId,
        @Param("requestId") String requestId,
        @Param("callbackUrl") String callbackUrl,
        @Param("goodsType") String goodsType,
        @Param("nextAttemptAt") OffsetDateTime nextAttemptAt
    );

    @Select("""
        SELECT id, user_id, request_id, order_no, callback_url, goods_type, state, attempt_count,
               next_attempt_at, lease_until, last_error, sent_at, created_at, updated_at
        FROM agiso_callback_tasks
        WHERE user_id = #{userId} AND request_id = #{requestId}
        LIMIT 1
        """)
    AgisoCallbackTaskEntity selectByUserAndRequest(
        @Param("userId") Long userId,
        @Param("requestId") String requestId
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET order_no = #{orderNo},
            next_attempt_at = #{nextAttemptAt}
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
          AND (order_no IS NULL OR order_no = #{orderNo})
          AND state NOT IN ('SENT', 'DEAD')
        """)
    int bindOrder(
        @Param("userId") Long userId,
        @Param("requestId") String requestId,
        @Param("orderNo") String orderNo,
        @Param("nextAttemptAt") OffsetDateTime nextAttemptAt
    );

    @Select("""
        SELECT id, user_id, request_id, order_no, callback_url, goods_type, state, attempt_count,
               next_attempt_at, lease_until, last_error, sent_at, created_at, updated_at
        FROM agiso_callback_tasks
        WHERE (state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
           OR (state = 'SENDING' AND lease_until IS NOT NULL AND lease_until <= #{now})
        ORDER BY COALESCE(next_attempt_at, lease_until, created_at), id
        LIMIT #{limit}
        """)
    List<AgisoCallbackTaskEntity> selectDue(
        @Param("now") OffsetDateTime now,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET state = 'SENDING',
            attempt_count = attempt_count + 1,
            lease_until = #{leaseUntil},
            next_attempt_at = NULL
        WHERE id = #{id}
          AND ((state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
            OR (state = 'SENDING' AND lease_until IS NOT NULL AND lease_until <= #{now}))
        """)
    int claim(
        @Param("id") Long id,
        @Param("now") OffsetDateTime now,
        @Param("leaseUntil") OffsetDateTime leaseUntil
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET next_attempt_at = #{nextAttemptAt}
        WHERE id = #{id} AND state = 'PENDING'
        """)
    int deferPending(
        @Param("id") Long id,
        @Param("nextAttemptAt") OffsetDateTime nextAttemptAt
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET state = 'PENDING',
            next_attempt_at = #{nextAttemptAt},
            lease_until = NULL,
            last_error = #{lastError}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int retry(
        @Param("id") Long id,
        @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
        @Param("lastError") String lastError
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET state = 'SENT',
            next_attempt_at = NULL,
            lease_until = NULL,
            last_error = NULL,
            sent_at = #{sentAt}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int markSent(
        @Param("id") Long id,
        @Param("sentAt") OffsetDateTime sentAt
    );

    @Update("""
        UPDATE agiso_callback_tasks
        SET state = 'DEAD',
            next_attempt_at = NULL,
            lease_until = NULL,
            last_error = #{lastError}
        WHERE id = #{id} AND state IN ('PENDING', 'SENDING')
        """)
    int markDead(
        @Param("id") Long id,
        @Param("lastError") String lastError
    );
}
