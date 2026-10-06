package com.example.robotdelivery.scheduler;

import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 冷藏监控巡检：周期性持续计算所有在途冷藏单的载物仓温度与剩余可配送时长，
 * 触发超时风险预警、必要时退回站点并生成处置选项。
 */
@Slf4j
@Component
public class ColdChainScheduler {

    private final ColdChainMonitorService monitorService;
    private final DeliveryProperties properties;

    public ColdChainScheduler(ColdChainMonitorService monitorService, DeliveryProperties properties) {
        this.monitorService = monitorService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${delivery.scheduler-interval-ms:10000}",
            initialDelayString = "${delivery.scheduler-interval-ms:10000}")
    public void monitorColdChain() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        try {
            monitorService.evaluateAll();
        } catch (Exception e) {
            log.error("冷藏监控巡检执行失败", e);
        }
    }
}
