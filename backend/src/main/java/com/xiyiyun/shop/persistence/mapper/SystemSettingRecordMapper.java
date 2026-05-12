package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.SystemSettingRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SystemSettingRecordMapper extends BaseMapper<SystemSettingRecordEntity> {
    @Update("""
        INSERT INTO system_settings (setting_key, setting_value)
        VALUES (#{entity.settingKey}, #{entity.settingValue})
        ON DUPLICATE KEY UPDATE
            setting_value = VALUES(setting_value)
        """)
    int upsertSetting(@Param("entity") SystemSettingRecordEntity entity);

    @Select("""
        SELECT setting_key, setting_value
        FROM system_settings
        """)
    List<SystemSettingRecordEntity> selectAllSettings();
}
