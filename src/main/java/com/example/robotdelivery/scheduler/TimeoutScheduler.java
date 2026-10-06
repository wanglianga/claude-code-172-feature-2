package com.example.robotdelivery.scheduler;

import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.coldchain.WaitAttribution;
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

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 超时巡检：用户超时不取、冷藏订单超时、电梯请求超时。
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

    public TimeoutScheduler(DeliveryOrderRepository orderRepository,
                            ElevatorRequestRepository elevatorRepository,
                            DeliveryEventService eventService,
                            CsService csService,
                            DeliveryProperties properties,
                            ColdChainMonitorService coldChainMonitorService) {
        this.orderRepository = orderRepository;
        this.elevatorRepository = elevatorRepository;
        this.eventService = eventService;
        this.csService = csService;
        this.properties = properties;
        this.coldChainMonitorService = coldChainMonitorService;
    }

    @Scheduled(fixedDelayString = "${delivery.scheduler-interval-ms:10000}",
            initialDelayString = "${delivery.scheduler-interval-ms:10000}")
    @Transactional
    public void checkTimeouts() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        try {
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

    /** 冷藏订单超时：超过冷藏履约时限仍未完成 -> 标记并触发客服介入 */
    private void checkColdChainTimeout() {
        List<DeliveryOrder> expired = orderRepository
                .findByColdChainTrueAndColdChainTimedOutFalseAndStatusNotInAndColdChainDeadlineBefore(
                        EnumSet.of(DeliveryStatus.SIGNED, DeliveryStatus.RETURNED,
                                DeliveryStatus.CANCELLED, DeliveryStatus.RETURNING),
                        LocalDateTime.now());
        for (DeliveryOrder order : expired) {
            order.setColdChainTimedOut(true);
            orderRepository.save(order);

            // 损失关联门禁等待、电梯等待和机器人路线（避障/绕行），供赔付判责
            WaitAttribution attr = coldChainMonitorService.attributeWaits(order, LocalDateTime.now());
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("coldChainDeadline", order.getColdChainDeadline().toString());
            payload.put("orderType", order.getOrderType().name());
            Map<String, Object> waits = new HashMap<>();
            waits.put("accessWaitSeconds", attr.accessWaitSeconds());
            waits.put("elevatorWaitSeconds", attr.elevatorWaitSeconds());
            waits.put("obstacleWaitSeconds", attr.obstacleWaitSeconds());
            waits.put("routeWaitSeconds", attr.routeWaitSeconds());
            waits.put("totalWaitSeconds", attr.totalWaitSeconds());
            waits.put("dominantReason", attr.dominantReason());
            payload.put("waitAttribution", waits);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_TIMEOUT, EventActor.SYSTEM, attr.dominantReason(),
                    attr.totalWaitSeconds(), payload);
            csService.openCase(order.getId(), CaseType.COLDCHAIN_TIMEOUT, Responsibility.PLATFORM,
                    String.format(
                            "冷藏订单超过履约时限(%d秒)未完成；损失关联等待 门禁=%ds 电梯=%ds 避障=%ds 路线=%ds（主导:%s）",
                            properties.getColdchainTimeoutSeconds(),
                            attr.accessWaitSeconds(), attr.elevatorWaitSeconds(),
                            attr.obstacleWaitSeconds(), attr.routeWaitSeconds(), attr.dominantReason()),
                    null);
            log.info("冷藏订单超时: orderNo={} 主导原因={}", order.getOrderNo(), attr.dominantReason());
        }
    }

    /** 电梯请求超时：电梯系统长时间未派梯 -> 标记超时并触发客服介入 */
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
            log.info("电梯请求超时: requestId={} deliveryId={}", request.getId(), order.getId());
        }
    }
}
