package com.example.robotdelivery.scheduler;

import com.example.robotdelivery.coldchain.ColdChainLossRepository;
import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.coldchain.ColdChainResolutionService;
import com.example.robotdelivery.coldchain.ColdGoodsStatus;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.elevator.ElevatorStatus;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 超时巡检：冷链持续温控预警、用户超时不取、冷藏订单超时、电梯请求超时。
 */
@Slf4j
@Component
public class TimeoutScheduler {

    private final DeliveryOrderRepository orderRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryEventService eventService;
    private final CsService csService;
    private final DeliveryProperties properties;
    private final ColdChainMonitorService coldChainMonitorService;
    private final ColdChainResolutionService coldChainResolutionService;
    private final ColdChainLossRepository lossRepository;

    public TimeoutScheduler(DeliveryOrderRepository orderRepository,
                            ElevatorRequestRepository elevatorRepository,
                            DeliveryEventService eventService,
                            CsService csService,
                            DeliveryProperties properties,
                            ColdChainMonitorService coldChainMonitorService,
                            ColdChainResolutionService coldChainResolutionService,
                            ColdChainLossRepository lossRepository) {
        this.orderRepository = orderRepository;
        this.elevatorRepository = elevatorRepository;
        this.eventService = eventService;
        this.csService = csService;
        this.properties = properties;
        this.coldChainMonitorService = coldChainMonitorService;
        this.coldChainResolutionService = coldChainResolutionService;
        this.lossRepository = lossRepository;
    }

    @Scheduled(fixedDelayString = "${delivery.scheduler-interval-ms:10000}",
            initialDelayString = "${delivery.scheduler-interval-ms:10000}")
    @Transactional
    public void checkTimeouts() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        try {
            // 先持续计算冷链温度/剩余时长并在超时前发出预警，再判定各类已超时
            coldChainMonitorService.monitorActiveOrders();
            checkPickupTimeout();
            checkColdChainTimeout();
            checkElevatorTimeout();
        } catch (Exception e) {
            log.error("超时巡检执行失败", e);
        }
    }

    /** 用户超时不取：通知后超过时限仍未签收 -> 标记并进入退回流程 */
    private void checkPickupTimeout() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(properties.getPickupTimeoutSeconds());
        List<DeliveryOrder> expired = orderRepository.findByStatusInAndNotifiedAtBefore(
                EnumSet.of(DeliveryStatus.WAITING_PICKUP, DeliveryStatus.COMPARTMENT_OPEN), deadline);
        for (DeliveryOrder order : expired) {
            order.setPickupTimedOut(true);
            order.setStatus(DeliveryStatus.RETURNING);
            order.setReturnReason("用户超时未取件");
            orderRepository.save(order);

            Map<String, Object> payload = new HashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("notifiedAt", order.getNotifiedAt().toString());
            payload.put("timeoutSeconds", properties.getPickupTimeoutSeconds());
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.PICKUP_TIMEOUT, EventActor.SYSTEM, payload);
            csService.openCase(order.getId(), CaseType.PICKUP_TIMEOUT, Responsibility.USER,
                    "用户超时未取件，机器人退回配送站", null);
            log.info("订单超时未取，进入退回流程: orderNo={}", order.getOrderNo());
        }
    }

    /** 冷藏订单超时：超过冷藏履约时限仍未完成 -> 标记、归因门禁/电梯/路线等待并触发客服介入与损失证据 */
    private void checkColdChainTimeout() {
        List<DeliveryOrder> expired = orderRepository
                .findByColdChainTrueAndColdChainTimedOutFalseAndStatusNotInAndColdChainDeadlineBefore(
                        EnumSet.of(DeliveryStatus.SIGNED, DeliveryStatus.RETURNED,
                                DeliveryStatus.CANCELLED, DeliveryStatus.RETURNING),
                        LocalDateTime.now());
        for (DeliveryOrder order : expired) {
            order.setColdChainTimedOut(true);
            orderRepository.save(order);

            ColdChainMonitorService.WaitStats stats =
                    coldChainMonitorService.computeStats(order, LocalDateTime.now());
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("coldChainDeadline", order.getColdChainDeadline().toString());
            payload.put("orderType", order.getOrderType().name());
            payload.put("tempCelsius", order.getLastTempCelsius());
            payload.put("accessWaitSeconds", stats.accessWaitSeconds());
            payload.put("elevatorWaitSeconds", stats.elevatorWaitSeconds());
            payload.put("obstacleWaitSeconds", stats.obstacleWaitSeconds());
            payload.put("routeAdjustmentCount", stats.routeAdjustments());
            payload.put("routeEvidence", stats.routeEvidence());
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_TIMEOUT, EventActor.SYSTEM,
                    dominantFactor(stats), stats.totalWaitSeconds(), payload);
            csService.openCase(order.getId(), CaseType.COLDCHAIN_TIMEOUT, Responsibility.PLATFORM,
                    "冷藏订单超过履约时限(" + properties.getColdchainTimeoutSeconds()
                            + "秒)未完成；门禁等待" + stats.accessWaitSeconds() + "s/电梯等待"
                            + stats.elevatorWaitSeconds() + "s/避障" + stats.obstacleWaitSeconds()
                            + "s，原配送证据保留用于赔付判断", null);

            // 自动留存损失归因证据（金额待客服核定，避免重复登记）
            if (lossRepository.findFirstByDelivery_IdOrderByIdDesc(order.getId()).isEmpty()) {
                coldChainResolutionService.recordLoss(order.getId(), BigDecimal.ZERO,
                        ColdGoodsStatus.AT_RISK, "SYSTEM",
                        "超时自动登记：损失金额待客服核定，已关联门禁/电梯/路线等待证据");
            }
            coldChainMonitorService.clearTrack(order.getId());
            log.info("冷藏订单超时: orderNo={} 门禁{}s 电梯{}s 路线调整{}次",
                    order.getOrderNo(), stats.accessWaitSeconds(), stats.elevatorWaitSeconds(),
                    stats.routeAdjustments());
        }
    }

    /** 电梯请求超时：电梯系统长时间未派梯 -> 标记超时并触发客服介入；同时立即重算冷链风险 */
    private void checkElevatorTimeout() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(properties.getElevatorTimeoutSeconds());
        List<ElevatorRequest> expired = elevatorRepository
                .findByStatusAndRequestedAtBefore(ElevatorStatus.PENDING, deadline);
        for (ElevatorRequest request : expired) {
            request.setStatus(ElevatorStatus.TIMEOUT);
            request.setResolvedAt(LocalDateTime.now());
            elevatorRepository.save(request);

            DeliveryOrder order = request.getDelivery();
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", request.getId());
            payload.put("orderNo", order.getOrderNo());
            payload.put("building", order.getBuilding().getName());
            payload.put("fromFloor", request.getFromFloor());
            payload.put("toFloor", request.getToFloor());
            payload.put("timeoutSeconds", properties.getElevatorTimeoutSeconds());
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.ELEVATOR_TIMEOUT, EventActor.SYSTEM, payload);
            csService.openCase(order.getId(), CaseType.ELEVATOR_TIMEOUT, Responsibility.ELEVATOR_SYSTEM,
                    "电梯请求超时未派梯(requestId=" + request.getId() + ")，建议人工接力或改走货梯", null);
            if (order.isColdChain()) {
                coldChainMonitorService.evaluateOrder(order.getId(), "电梯超时，重算冷链风险");
            }
            log.info("电梯请求超时: requestId={} deliveryId={}", request.getId(), order.getId());
        }
    }

    private String dominantFactor(ColdChainMonitorService.WaitStats stats) {
        String factor = "COMPARTMENT_WARMING";
        long max = 0;
        if (stats.accessWaitSeconds() > max) {
            max = stats.accessWaitSeconds();
            factor = "ACCESS_WAIT";
        }
        if (stats.elevatorWaitSeconds() > max) {
            max = stats.elevatorWaitSeconds();
            factor = "ELEVATOR_WAIT";
        }
        if (stats.obstacleWaitSeconds() > max) {
            factor = "OBSTACLE_WAIT";
        }
        return factor;
    }
}
