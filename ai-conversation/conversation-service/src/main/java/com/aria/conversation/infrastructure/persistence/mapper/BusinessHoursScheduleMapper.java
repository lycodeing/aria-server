package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessHoursScheduleMapper extends BaseMapper<BusinessHoursScheduleEntity> {

    default BusinessHoursScheduleEntity selectByDayOfWeek(int dayOfWeek) {
        return selectOne(Wrappers.<BusinessHoursScheduleEntity>lambdaQuery()
                .eq(BusinessHoursScheduleEntity::getDayOfWeek, dayOfWeek));
    }
}
