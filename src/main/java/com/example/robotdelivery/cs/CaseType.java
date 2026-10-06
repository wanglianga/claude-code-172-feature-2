package com.example.robotdelivery.cs;

public enum CaseType {
    ACCESS_DENIED,       // 物业门禁拒绝
    NIGHT_RESTRICTED,    // 夜间限制运行
    LOW_BATTERY,         // 机器人电量不足
    COMPARTMENT_FAULT,   // 载物仓打不开
    PICKUP_TIMEOUT,      // 用户超时不取
    COLDCHAIN_TIMEOUT,   // 冷藏订单超时
    COLDCHAIN_RISK,      // 冷藏订单超时风险预警（尚未超时，提前介入）
    ELEVATOR_TIMEOUT,    // 电梯请求超时
    COMPLAINT,           // 用户投诉（送错楼栋等）
    OTHER
}
