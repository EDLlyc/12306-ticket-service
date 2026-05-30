package com.ahu.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
    private Integer stock; // 兼容老逻辑，详细库存模式下镜像 available_stock
    @TableField("available_stock")
    private Integer availableStock;
    @TableField("locked_stock")
    private Integer lockedStock;
    @TableField("sold_stock")
    private Integer soldStock;
}
