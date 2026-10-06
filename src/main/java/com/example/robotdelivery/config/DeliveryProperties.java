package com.example.robotdelivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {

    /** 用户超时不取件秒数 */
    private long pickupTimeoutSeconds = 600;

    /** 冷藏订单履约时限秒数 */
    private long coldchainTimeoutSeconds = 2700;

    /** 电梯请求超时秒数 */
    private long elevatorTimeoutSeconds = 180;

    /** 低电量阈值（百分比） */
    private int lowBatteryThreshold = 20;

    /** 超时巡检间隔毫秒 */
    private long schedulerIntervalMs = 10000;

    /** 是否开启超时巡检 */
    private boolean schedulerEnabled = true;

    // ---- 冷链温控参数 ----

    /** 载物仓标准冷藏温度（℃），持续计算的基准温度 */
    private double coldchainBaseTempCelsius = 4.0;

    /** 预警温度（℃）：达到该温度发出超时风险预警 */
    private double coldchainWarnTempCelsius = 6.0;

    /** 严重风险温度（℃）：达到该温度必要时退回站点更换商品 */
    private double coldchainCriticalTempCelsius = 8.0;

    /** 等待时（门禁/电梯/避障）载物仓升温速率（℃/分钟） */
    private double coldchainWarmRatePerMinute = 4.0;

    /** 移动/正常制冷时载物仓回冷速率（℃/分钟） */
    private double coldchainCoolRatePerMinute = 2.0;

    /** 剩余可配送时长低于履约时限的该比例时发出预警（0-1） */
    private double coldchainWarnRemainingRatio = 0.30;

    /** 剩余可配送时长低于履约时限的该比例时升级严重风险（0-1） */
    private double coldchainCriticalRemainingRatio = 0.12;
}
