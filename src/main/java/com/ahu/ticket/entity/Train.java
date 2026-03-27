package com.ahu.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_train")
public class Train {
    @TableId
    private Long id;
    private String trainNumber;
    private String startStation;
    private String endStation;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer stock; // 对应数据库中的 stock 字段
}