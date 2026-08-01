package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.AdminStaffRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：后台员工账号（原 system_settings 的 admin.staff.accounts JSON 数组）。
 *
 * <p>整数组读写改成按行 upsert 之后，删除必须有真删除语句，否则被删员工会在
 * 「整数组重写」消失、而按行 upsert 下永久留存并仍可登录。
 */
@Mapper
public interface AdminStaffRecordMapper extends BaseMapper<AdminStaffRecordEntity> {
    @Update("""
        INSERT INTO admin_staff (id, account, nickname, status, permissions, password_hash, created_at, updated_at)
        VALUES (
            #{entity.id}, #{entity.account}, #{entity.nickname}, #{entity.status},
            #{entity.permissions}, #{entity.passwordHash},
            COALESCE(#{entity.createdAt}, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3)
        )
        ON DUPLICATE KEY UPDATE
            account = VALUES(account),
            nickname = VALUES(nickname),
            status = VALUES(status),
            permissions = VALUES(permissions),
            password_hash = VALUES(password_hash),
            updated_at = CURRENT_TIMESTAMP(3)
        """)
    int upsertSnapshot(@Param("entity") AdminStaffRecordEntity entity);

    @Select("""
        SELECT id, account, nickname, status, permissions, password_hash, created_at, updated_at
        FROM admin_staff
        ORDER BY id
        """)
    List<AdminStaffRecordEntity> selectAllSnapshots();

    /**
     * 删除快照里已经不存在的员工。
     *
     * <p>用 foreach 绑参而不是字符串拼 {@code ${ids}}：后者会把调用方的数据直接拼进 SQL，
     * 是注入面。空列表时退化为「全删」，与「快照为空表示没有员工」一致。
     */
    @Update("""
        <script>
        DELETE FROM admin_staff
        <if test="ids != null and ids.size() > 0">
            WHERE id NOT IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    int deleteMissing(@Param("ids") List<Long> keptIds);
}
