package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.SupplierRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SupplierRecordMapper extends BaseMapper<SupplierRecordEntity> {
    @Update("""
        INSERT INTO suppliers (
            id, name, platform_type, base_url, app_key, app_secret_masked,
            user_id, app_id, api_key_masked, callback_url, timeout_seconds,
            balance, status, remark, last_sync_at
        ) VALUES (
            #{entity.id}, #{entity.name}, #{entity.platformType}, #{entity.baseUrl}, #{entity.appKey}, #{entity.appSecretMasked},
            #{entity.userId}, #{entity.appId}, #{entity.apiKeyMasked}, #{entity.callbackUrl}, #{entity.timeoutSeconds},
            #{entity.balance}, #{entity.status}, #{entity.remark}, #{entity.lastSyncAt}
        )
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            platform_type = VALUES(platform_type),
            base_url = VALUES(base_url),
            app_key = VALUES(app_key),
            app_secret_masked = VALUES(app_secret_masked),
            user_id = VALUES(user_id),
            app_id = VALUES(app_id),
            api_key_masked = VALUES(api_key_masked),
            callback_url = VALUES(callback_url),
            timeout_seconds = VALUES(timeout_seconds),
            balance = VALUES(balance),
            status = VALUES(status),
            remark = VALUES(remark),
            last_sync_at = VALUES(last_sync_at),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") SupplierRecordEntity entity);

    @Update("UPDATE suppliers SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Select("""
        SELECT id, name, platform_type, base_url, app_key, app_secret_masked,
               user_id, app_id, api_key_masked, callback_url, timeout_seconds,
               balance, status, remark, last_sync_at
        FROM suppliers
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<SupplierRecordEntity> selectActiveSnapshots();
}
