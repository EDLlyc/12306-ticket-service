package com.ahu.ticket.service.impl;

import com.ahu.ticket.entity.Train;
import com.ahu.ticket.mapper.TrainMapper;
import com.ahu.ticket.service.ITrainService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service // 别忘了这个注解，否则 Controller 找不到它
public class TrainServiceImpl extends ServiceImpl<TrainMapper, Train> implements ITrainService {
}