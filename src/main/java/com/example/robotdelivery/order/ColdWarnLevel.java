package com.example.robotdelivery.order;

/**
 * 冷链订单超时风险预警级别。
 * NONE：正常；WARN：存在超时风险，提前通知客服与用户；
 * CRITICAL：已无法在温控时限内完成，必要时退回站点更换商品。
 */
public enum ColdWarnLevel {
    NONE,
    WARN,
    CRITICAL
}
