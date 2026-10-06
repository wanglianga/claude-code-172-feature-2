package com.example.robotdelivery.cs;

public enum CaseType {
    ACCESS_DENIED,       // 物业门禁拒绝
    NIGHT_RESTRICTED,    // 夜间限制运行
    LOW_BATTERY,         // 机器人电量不足
    COMPARTMENT_FAULT,   // 载物仓打不开
    PICKUP_TIMEOUT,      // 用户超时不取
    COLDCHAIN_TIMEOUT,   // 冷藏订单超时
    COLDCHAIN_RISK,      // 冷藏超时风险预警/退回（损失关联门禁、电梯与机器人路线，待赔付判责）
    ELEVATOR_TIMEOUT,    // 电梯请求超时
    COMPLAINT,           // 用户投诉（送错楼栋等）
    OTHER
}
