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

    /** 冷藏超时风险提前预警秒数：剩余可配送时长不足该值即发出 WARNING */
    private long coldchainWarnLeadSeconds = 600;

    /** 冷藏严重风险提前量秒数：剩余时长不足该值（或温度达到临界）判定 CRITICAL，必要时退回站点 */
    private long coldchainCriticalLeadSeconds = 300;

    /** 载物仓安全温度（℃，出库基准温度） */
    private double coldchainTempSafeC = 2.0;

    /** 载物仓预警温度（℃） */
    private double coldchainTempWarnC = 8.0;

    /** 载物仓临界温度（℃），达到即判定商品可能失温 */
    private double coldchainTempCriticalC = 10.0;

    /** 每累计等待 1 分钟的附加温升（℃），门禁/电梯/避障等待会推高仓温 */
    private double coldchainTempRisePerWaitMinute = 0.5;

    /** CRITICAL 风险时是否自动退回站点更换商品 */
    private boolean coldchainAutoReturnEnabled = true;
}
