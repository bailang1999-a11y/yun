package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.MemberOrderCallbackTaskEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MemberOrderCallbackTaskMapper extends BaseMapper<MemberOrderCallbackTaskEntity> {
    @Update("""
        INSERT INTO member_order_callback_tasks (
            event_id, user_id, request_id, order_no, event_type, order_status,
            callback_url, payload_json, sensitive_ciphertext, sensitive_nonce, sensitive_key_version,
            state, attempt_count, next_attempt_at
        )
        SELECT #{eventId}, #{userId}, #{requestId}, #{orderNo}, #{eventType}, #{orderStatus},
               credential.callback_url, CAST(#{payloadJson} AS JSON),
               #{sensitiveCiphertext}, #{sensitiveNonce}, #{sensitiveKeyVersion}, 'PENDING', 0, #{now}
        FROM member_api_credentials credential
        WHERE credential.user_id = #{userId}
          AND credential.callback_url IS NOT NULL
          AND TRIM(credential.callback_url) <> ''
          AND NOT EXISTS (
              SELECT 1
              FROM agiso_callback_tasks agiso
              WHERE agiso.user_id = #{userId}
                AND agiso.request_id = #{requestId}
          )
        ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
        """)
    int registerPending(
        @Param("eventId") String eventId,
        @Param("userId") Long userId,
        @Param("requestId") String requestId,
        @Param("orderNo") String orderNo,
        @Param("eventType") String eventType,
        @Param("orderStatus") String orderStatus,
        @Param("payloadJson") String payloadJson,
        @Param("sensitiveCiphertext") byte[] sensitiveCiphertext,
        @Param("sensitiveNonce") byte[] sensitiveNonce,
        @Param("sensitiveKeyVersion") String sensitiveKeyVersion,
        @Param("now") OffsetDateTime now
    );

    @Select("""
        SELECT id, event_id, user_id, request_id, order_no, event_type, order_status,
               callback_url, payload_json, state, attempt_count, next_attempt_at,
               sensitive_ciphertext, sensitive_nonce, sensitive_key_version,
               lease_until, last_error, sent_at, created_at, updated_at
        FROM member_order_callback_tasks
        WHERE event_id = #{eventId}
        LIMIT 1
        """)
    MemberOrderCallbackTaskEntity selectByEventId(@Param("eventId") String eventId);

    @Select("""
        SELECT id, event_id, user_id, request_id, order_no, event_type, order_status,
               callback_url, payload_json, state, attempt_count, next_attempt_at,
               sensitive_ciphertext, sensitive_nonce, sensitive_key_version,
               lease_until, last_error, sent_at, created_at, updated_at
        FROM member_order_callback_tasks
        WHERE (state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
           OR (state = 'SENDING' AND lease_until IS NOT NULL AND lease_until <= #{now})
        ORDER BY COALESCE(next_attempt_at, lease_until, created_at), id
        LIMIT #{limit}
        """)
    List<MemberOrderCallbackTaskEntity> selectDue(
        @Param("now") OffsetDateTime now,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE member_order_callback_tasks
        SET state = 'SENDING', attempt_count = attempt_count + 1,
            lease_until = #{leaseUntil}, next_attempt_at = NULL
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
        UPDATE member_order_callback_tasks
        SET state = 'PENDING', next_attempt_at = #{nextAttemptAt},
            lease_until = NULL, last_error = #{lastError}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int retry(
        @Param("id") Long id,
        @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
        @Param("lastError") String lastError
    );

    @Update("""
        UPDATE member_order_callback_tasks
        SET state = 'SENT', next_attempt_at = NULL, lease_until = NULL,
            last_error = NULL, sent_at = #{sentAt}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int markSent(@Param("id") Long id, @Param("sentAt") OffsetDateTime sentAt);

    @Update("""
        UPDATE member_order_callback_tasks
        SET state = 'DEAD', next_attempt_at = NULL, lease_until = NULL, last_error = #{lastError}
        WHERE id = #{id} AND state IN ('PENDING', 'SENDING')
        """)
    int markDead(@Param("id") Long id, @Param("lastError") String lastError);

    @Update("""
        UPDATE member_order_callback_tasks
        SET state = 'CANCELLED', next_attempt_at = NULL, lease_until = NULL, last_error = #{reason}
        WHERE id = #{id} AND state IN ('PENDING', 'SENDING')
        """)
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
        UPDATE member_order_callback_tasks
        SET state = 'CANCELLED', next_attempt_at = NULL, lease_until = NULL, last_error = #{reason}
        WHERE user_id = #{userId}
          AND state IN ('PENDING', 'SENDING')
          AND (#{callbackUrl} = '' OR callback_url <> #{callbackUrl})
        """)
    int cancelPendingForUserExceptUrl(
        @Param("userId") Long userId,
        @Param("callbackUrl") String callbackUrl,
        @Param("reason") String reason
    );

    @Delete("DELETE FROM member_order_callback_tasks WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
