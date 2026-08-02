package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.WeComRobotDeliveryTaskEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WeComRobotDeliveryTaskMapper extends BaseMapper<WeComRobotDeliveryTaskEntity> {
    @Update("""
        INSERT INTO wecom_robot_delivery_tasks (
            event_id, event_type, order_no, markdown_content, state, attempt_count, next_attempt_at
        )
        SELECT #{eventId}, #{eventType}, #{orderNo}, #{markdownContent}, 'PENDING', 0, #{now}
        FROM system_settings enabled
        JOIN system_settings events ON events.setting_key = 'wecom.robot.events'
        WHERE enabled.setting_key = 'wecom.robot.enabled'
          AND LOWER(TRIM(enabled.setting_value)) = 'true'
          AND FIND_IN_SET(#{eventType}, events.setting_value) > 0
        ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
        """)
    int registerOrderEvent(
        @Param("eventId") String eventId,
        @Param("eventType") String eventType,
        @Param("orderNo") String orderNo,
        @Param("markdownContent") String markdownContent,
        @Param("now") OffsetDateTime now
    );

    @Select("""
        SELECT id, event_id, event_type, order_no, markdown_content, state, attempt_count,
               next_attempt_at, lease_until, last_error, sent_at, created_at, updated_at
        FROM wecom_robot_delivery_tasks
        WHERE id = #{id}
        """)
    WeComRobotDeliveryTaskEntity selectTask(@Param("id") Long id);

    @Select("""
        SELECT id, event_id, event_type, order_no, markdown_content, state, attempt_count,
               next_attempt_at, lease_until, last_error, sent_at, created_at, updated_at
        FROM wecom_robot_delivery_tasks
        WHERE (state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
           OR (state = 'SENDING' AND lease_until IS NOT NULL AND lease_until <= #{now})
        ORDER BY COALESCE(next_attempt_at, lease_until, created_at), id
        LIMIT #{limit}
        """)
    List<WeComRobotDeliveryTaskEntity> selectDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Select("""
        SELECT id, event_id, event_type, order_no, markdown_content, state, attempt_count,
               next_attempt_at, lease_until, last_error, sent_at, created_at, updated_at
        FROM wecom_robot_delivery_tasks
        ORDER BY id DESC
        LIMIT #{limit}
        """)
    List<WeComRobotDeliveryTaskEntity> selectLatest(@Param("limit") int limit);

    @Update("""
        UPDATE wecom_robot_delivery_tasks
        SET state = 'SENDING', attempt_count = attempt_count + 1,
            lease_until = #{leaseUntil}, next_attempt_at = NULL
        WHERE id = #{id}
          AND ((state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
            OR (state = 'SENDING' AND lease_until IS NOT NULL AND lease_until <= #{now}))
        """)
    int claim(@Param("id") Long id, @Param("now") OffsetDateTime now, @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
        UPDATE wecom_robot_delivery_tasks
        SET state = 'PENDING', next_attempt_at = #{nextAttemptAt}, lease_until = NULL, last_error = #{lastError}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int retry(@Param("id") Long id, @Param("nextAttemptAt") OffsetDateTime nextAttemptAt, @Param("lastError") String lastError);

    @Update("""
        UPDATE wecom_robot_delivery_tasks
        SET state = 'SENT', next_attempt_at = NULL, lease_until = NULL, last_error = NULL, sent_at = #{sentAt}
        WHERE id = #{id} AND state = 'SENDING'
        """)
    int markSent(@Param("id") Long id, @Param("sentAt") OffsetDateTime sentAt);

    @Update("""
        UPDATE wecom_robot_delivery_tasks
        SET state = 'DEAD', next_attempt_at = NULL, lease_until = NULL, last_error = #{lastError}
        WHERE id = #{id} AND state IN ('PENDING', 'SENDING')
        """)
    int markDead(@Param("id") Long id, @Param("lastError") String lastError);

    @Update("""
        UPDATE wecom_robot_delivery_tasks
        SET state = 'CANCELLED', next_attempt_at = NULL, lease_until = NULL, last_error = #{reason}
        WHERE id = #{id} AND state IN ('PENDING', 'SENDING')
        """)
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    @Delete("DELETE FROM wecom_robot_delivery_tasks WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
