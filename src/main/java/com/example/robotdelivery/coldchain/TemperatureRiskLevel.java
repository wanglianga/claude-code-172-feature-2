package com.example.robotdelivery.coldchain;

/**
 * 冷藏超时风险等级：
 * NORMAL 正常；WARNING 超时风险（提前预警客服与用户）；CRITICAL 严重风险（必要时退回站点换品）。
 */
public enum TemperatureRiskLevel {
    NORMAL,
    WARNING,
    CRITICAL
}
