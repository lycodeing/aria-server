package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDate;

@Mapper
public interface BusinessHoursHolidayMapper extends BaseMapper<BusinessHoursHolidayEntity> {

    default BusinessHoursHolidayEntity selectByDate(LocalDate date) {
        return selectOne(Wrappers.<BusinessHoursHolidayEntity>lambdaQuery()
                .eq(BusinessHoursHolidayEntity::getDate, date));
    }
}
