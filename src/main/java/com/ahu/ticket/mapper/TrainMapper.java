package com.ahu.ticket.mapper;

import com.ahu.ticket.entity.Train;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TrainMapper extends BaseMapper<Train> {
    // 继承了 BaseMapper，你就拥有了所有 CRUD 能力
}