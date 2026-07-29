package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursHolidayEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BusinessHoursHolidayMapper extends BaseMapper<BusinessHoursHolidayEntity> {

    default BusinessHoursHolidayEntity selectByDate(LocalDate date) {
        return selectOne(Wrappers.<BusinessHoursHolidayEntity>lambdaQuery()
                .eq(BusinessHoursHolidayEntity::getDate, date));
    }

    /**
     * 批量写入节假日（upsert，SQL 见 BusinessHoursHolidayMapper.xml）。
     *
     * <ul>
     *   <li>日期不存在 → INSERT</li>
     *   <li>日期已存在且 source=AUTO → UPDATE（同步最新数据）</li>
     *   <li>日期已存在且 source=MANUAL → 跳过，保护管理员手动录入</li>
     * </ul>
     *
     * @param list 待写入的节假日实体列表（不能为空）
     * @return 实际影响行数（含新增和更新）
     */
    int insertBatch(@Param("list") List<BusinessHoursHolidayEntity> list);
}
