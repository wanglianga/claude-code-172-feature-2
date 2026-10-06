package com.example.robotdelivery.event;

/**
 * 统一配送事件类型：门禁开通、路线调整、电梯请求、避障停留、签收、退回、客服介入等
 * 全部写入同一配送事件流（Kafka topic: delivery-events -> delivery_event 表）。
 */
public enum EventType {
    ORDER_CREATED,          // 订单创建
    ROBOT_ASSIGNED,         // 分配机器人与载物仓
    ACCESS_REQUESTED,       // 机器人到小区门口，申请物业门禁
    ACCESS_GRANTED,         // 物业门禁开通
    ACCESS_DENIED,          // 物业门禁拒绝
    NIGHT_RESTRICTED,       // 物业夜间限制运行
    ELEVATOR_REQUESTED,     // 与电梯系统协同请求楼层
    ELEVATOR_ASSIGNED,      // 电梯系统派梯
    ELEVATOR_FULL,          // 电梯满载
    ELEVATOR_TIMEOUT,       // 电梯请求超时
    ELEVATOR_COMPLETED,     // 乘梯完成到达目标楼层
    OBSTACLE_WAIT,          // 避障停留（老人儿童挡路等）
    ROUTE_ADJUSTED,         // 路线调整（施工绕行/电梯满载/送错楼栋等）
    LOW_BATTERY,            // 机器人电量不足
    ARRIVED_AT_DOOR,        // 到达用户门口
    USER_NOTIFIED,          // 短信/小程序通知用户取件
    COMPARTMENT_OPENED,     // 载物仓打开
    COMPARTMENT_OPEN_FAILED,// 载物仓打不开
    SIGNED,                 // 用户签收
    PICKUP_TIMEOUT,         // 用户超时不取
    COLDCHAIN_TIMEOUT,      // 冷藏订单超时
    COLDCHAIN_WARNING,      // 冷藏超时风险预警（提前通知客服与用户）
    COLDCHAIN_CRITICAL,     // 冷藏风险升级为严重（必要时退回换品）
    TEMP_CALCULATED,        // 持续计算载物仓温度与剩余可配送时长
    RETURN_FOR_SWAP,        // 冷链风险退回站点更换商品（原配送证据保留）
    GOODS_SWAPPED,          // 站点已更换商品
    COLDCHAIN_REDELIVER_OPTION, // 退回站点后生成重新配送选项
    COLDCHAIN_REFUND_OPTION,    // 退回站点后生成退款选项
    RESTOCK_NOTIFY,         // 通知门店是否需要重新备货
    COLDCHAIN_LOSS_ASSESSED,   // 冷链超时损失评估（关联门禁/电梯/路线证据）
    RETURN_STARTED,         // 开始退回
    RETURNED,               // 退回完成
    MANUAL_RELAY,           // 人工接力
    COMPLAINT_FILED,        // 用户投诉（如送错楼栋）
    CS_INTERVENTION,        // 客服介入
    CS_CASE_CLOSED,         // 客服工单关闭
    DELIVERY_CANCELLED,     // 配送取消
    RULE_UPDATED            // 物业规则变更（社区级事件，deliveryId 为空）
}
