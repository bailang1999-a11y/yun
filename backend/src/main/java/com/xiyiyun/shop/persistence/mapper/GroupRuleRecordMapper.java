package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupRuleRecordMapper extends BaseMapper<GroupRuleRecordEntity> {
    @Update("""
        INSERT INTO group_goods_rules (group_id, rule_type, target_id, target_code, permission)
        VALUES (#{entity.groupId}, #{entity.ruleType}, #{entity.targetId}, #{entity.targetCode}, #{entity.permission})
        ON DUPLICATE KEY UPDATE
            permission = VALUES(permission),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") GroupRuleRecordEntity entity);

    @Delete("DELETE FROM group_goods_rules WHERE group_id = #{groupId} AND rule_type = #{ruleType}")
    int deleteByGroupAndType(@Param("groupId") Long groupId, @Param("ruleType") String ruleType);

    @Select("""
        SELECT id, group_id, rule_type, target_id, target_code, permission
        FROM group_goods_rules
        WHERE deleted_at IS NULL
        ORDER BY group_id, rule_type, target_id, target_code
        """)
    List<GroupRuleRecordEntity> selectActiveSnapshots();
}
