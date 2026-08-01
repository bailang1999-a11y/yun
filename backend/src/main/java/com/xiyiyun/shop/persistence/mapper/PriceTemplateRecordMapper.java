package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PriceTemplateRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：价格模板（原 system_settings 的 price.templates JSON 数组）。
 */
@Mapper
public interface PriceTemplateRecordMapper extends BaseMapper<PriceTemplateRecordEntity> {
    @Update("""
        INSERT INTO price_templates (
            template_id, name, adjust_mode, reference_price, group_rates, enabled, sort_no
        ) VALUES (
            #{entity.templateId}, #{entity.name}, #{entity.adjustMode}, #{entity.referencePrice},
            #{entity.groupRates}, #{entity.enabled}, #{entity.sortNo}
        )
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            adjust_mode = VALUES(adjust_mode),
            reference_price = VALUES(reference_price),
            group_rates = VALUES(group_rates),
            enabled = VALUES(enabled),
            sort_no = VALUES(sort_no)
        """)
    int upsertSnapshot(@Param("entity") PriceTemplateRecordEntity entity);

    @Select("""
        SELECT template_id, name, adjust_mode, reference_price, group_rates, enabled, sort_no
        FROM price_templates
        ORDER BY sort_no, template_id
        """)
    List<PriceTemplateRecordEntity> selectAllSnapshots();

    @Update("""
        <script>
        DELETE FROM price_templates
        <if test="ids != null and ids.size() > 0">
            WHERE template_id NOT IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    int deleteMissing(@Param("ids") List<String> keptIds);
}
