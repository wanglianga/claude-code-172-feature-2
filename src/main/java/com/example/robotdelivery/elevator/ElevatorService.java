package com.example.robotdelivery.elevator;

import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 电梯系统协同：机器人进入楼栋后请求电梯选择楼层；
 * 电梯满载时自动重新排队（路线调整），等待超时由巡检任务处理。
 */
@Service
public class ElevatorService {

    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryOrderRepository orderRepository;
    private final DeliveryEventService eventService;
    private final ColdChainMonitorService coldChainMonitorService;

    public ElevatorService(ElevatorRequestRepository elevatorRepository,
                           DeliveryOrderRepository orderRepository,
                           DeliveryEventService eventService,
                           ColdChainMonitorService coldChainMonitorService) {
        this.elevatorRepository = elevatorRepository;
        this.orderRepository = orderRepository;
        this.eventService = eventService;
        this.coldChainMonitorService = coldChainMonitorService;
    }

    @Transactional
    public ElevatorRequest request(Long deliveryId, Integer fromFloor, Integer toFloor) {
        DeliveryOrder order = mustGet(deliveryId);
        if (order.getStatus() != DeliveryStatus.ENTERING) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许请求电梯（需先通过门禁进入楼栋）");
        }
        ElevatorRequest request = createRequest(order, fromFloor, toFloor);

        order.setStatus(DeliveryStatus.ELEVATOR_WAIT);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, request));
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "电梯等待，持续计算载物仓温度");
        }
        return request;
    }

    /** 电梯系统派梯 */
    @Transactional
    public ElevatorRequest assign(Long requestId, String carId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.PENDING) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许派梯");
        }
        request.setStatus(ElevatorStatus.ASSIGNED);
        request.setCarId(carId);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        Map<String, Object> payload = payload(order, request);
        payload.put("carId", carId);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_ASSIGNED, EventActor.ELEVATOR, payload);
        return request;
    }

    /** 电梯系统上报满载：机器人等待下一趟（自动重新请求，记为路线调整） */
    @Transactional
    public ElevatorRequest full(Long requestId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.PENDING) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许标记满载");
        }
        request.setStatus(ElevatorStatus.FULL);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_FULL, EventActor.ELEVATOR, payload(order, request));

        // 路线调整：等待下一趟电梯，自动重新请求
        Map<String, Object> adjustPayload = payload(order, request);
        adjustPayload.put("detail", "电梯满载，机器人等待下一趟并重新请求");
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROUTE_ADJUSTED, EventActor.SYSTEM, "ELEVATOR_FULL", null, adjustPayload);

        ElevatorRequest retry = createRequest(order, request.getFromFloor(), request.getToFloor());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_REQUESTED, EventActor.ROBOT, payload(order, retry));
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "电梯满载等待下一趟，持续计算载物仓温度");
        }
        return retry;
    }

    /** 乘梯完成，到达目标楼层 */
    @Transactional
    public ElevatorRequest complete(Long requestId) {
        ElevatorRequest request = mustGetRequest(requestId);
        if (request.getStatus() != ElevatorStatus.ASSIGNED) {
            throw ApiException.conflict("电梯请求状态[" + request.getStatus() + "]不允许完成（需先派梯）");
        }
        request.setStatus(ElevatorStatus.COMPLETED);
        request.setResolvedAt(LocalDateTime.now());
        elevatorRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        order.setStatus(DeliveryStatus.ON_FLOOR);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ELEVATOR_COMPLETED, EventActor.ELEVATOR, payload(order, request));
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "乘梯完成，累计电梯等待已计入温控");
        }
        return request;
    }

    private ElevatorRequest createRequest(DeliveryOrder order, Integer fromFloor, Integer toFloor) {
        ElevatorRequest request = new ElevatorRequest();
        request.setDelivery(order);
        request.setBuildingId(order.getBuilding().getId());
        request.setFromFloor(fromFloor);
        request.setToFloor(toFloor);
        request.setStatus(ElevatorStatus.PENDING);
        return elevatorRepository.save(request);
    }

    private Map<String, Object> payload(DeliveryOrder order, ElevatorRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("orderNo", order.getOrderNo());
        payload.put("building", order.getBuilding().getName());
        payload.put("fromFloor", request.getFromFloor());
        payload.put("toFloor", request.getToFloor());
        if (request.getCarId() != null) {
            payload.put("carId", request.getCarId());
        }
        return payload;
    }

    private DeliveryOrder mustGet(Long deliveryId) {
        return orderRepository.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + deliveryId));
    }

    private ElevatorRequest mustGetRequest(Long requestId) {
        return elevatorRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("电梯请求不存在: " + requestId));
    }
}
