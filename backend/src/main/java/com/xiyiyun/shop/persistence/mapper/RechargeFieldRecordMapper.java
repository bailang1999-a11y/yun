package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.RechargeFieldRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RechargeFieldRecordMapper extends BaseMapper<RechargeFieldRecordEntity> {
    @Update("""
        INSERT INTO recharge_fields (
            id, code, label, placeholder, help_text, input_type, is_required,
            sort_no, enabled, created_at, updated_at
        ) VALUES (
            #{entity.id}, #{entity.code}, #{entity.label}, #{entity.placeholder}, #{entity.helpText}, #{entity.inputType}, #{entity.required},
            #{entity.sort}, #{entity.enabled}, #{entity.createdAt}, #{entity.updatedAt}
        )
        ON DUPLICATE KEY UPDATE
            code = VALUES(code),
            label = VALUES(label),
            placeholder = VALUES(placeholder),
            help_text = VALUES(help_text),
            input_type = VALUES(input_type),
            is_required = VALUES(is_required),
            sort_no = VALUES(sort_no),
            enabled = VALUES(enabled),
            updated_at = VALUES(updated_at),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") RechargeFieldRecordEntity entity);

    @Update("UPDATE recharge_fields SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Select("""
        SELECT id, code, label, placeholder, help_text, input_type,
               is_required AS required, sort_no AS sort, enabled, created_at, updated_at
        FROM recharge_fields
        WHERE deleted_at IS NULL
        ORDER BY sort_no, id
        """)
    List<RechargeFieldRecordEntity> selectActiveSnapshots();
}
