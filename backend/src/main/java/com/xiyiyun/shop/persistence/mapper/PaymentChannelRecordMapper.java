package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PaymentChannelRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：支付渠道（原 system_settings 的 payment.channels JSON 数组）。
 *
 * <p>公开配置与密文信封分列 config_public / config_secrets，沿用批次前
 * {@code putEncryptedConfig} 的 {@code key} + {@code keySecrets} 拆分语义，
 * 明文密钥不落库。
 */
@Mapper
public interface PaymentChannelRecordMapper extends BaseMapper<PaymentChannelRecordEntity> {
    @Update("""
        INSERT INTO payment_channels (
            id, code, name, channel_type, terminals, status, sort_no,
            config_public, config_secrets, remark, created_at, updated_at
        ) VALUES (
            #{entity.id}, #{entity.code}, #{entity.name}, #{entity.channelType}, #{entity.terminals},
            #{entity.status}, #{entity.sortNo}, #{entity.configPublic}, #{entity.configSecrets}, #{entity.remark},
            COALESCE(#{entity.createdAt}, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3)
        )
        ON DUPLICATE KEY UPDATE
            code = VALUES(code),
            name = VALUES(name),
            channel_type = VALUES(channel_type),
            terminals = VALUES(terminals),
            status = VALUES(status),
            sort_no = VALUES(sort_no),
            config_public = VALUES(config_public),
            config_secrets = VALUES(config_secrets),
            remark = VALUES(remark),
            updated_at = CURRENT_TIMESTAMP(3)
        """)
    int upsertSnapshot(@Param("entity") PaymentChannelRecordEntity entity);

    @Select("""
        SELECT id, code, name, channel_type, terminals, status, sort_no,
               config_public, config_secrets, remark, created_at, updated_at
        FROM payment_channels
        ORDER BY sort_no, id
        """)
    List<PaymentChannelRecordEntity> selectAllSnapshots();

    @Update("""
        <script>
        DELETE FROM payment_channels
        <if test="ids != null and ids.size() > 0">
            WHERE id NOT IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    int deleteMissing(@Param("ids") List<Long> keptIds);
}
