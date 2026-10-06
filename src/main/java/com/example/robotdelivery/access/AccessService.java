package com.example.robotdelivery.access;

import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 物业门禁协同：机器人到小区门口申请门禁，物业开通或拒绝；
 * 物业要求限制夜间运行时，夜间时段的申请自动拒绝。
 */
@Service
public class AccessService {

    private final AccessRequestRepository accessRepository;
    private final DeliveryOrderRepository orderRepository;
    private final DeliveryEventService eventService;
    private final CsService csService;
    private final ColdChainMonitorService coldChainMonitorService;

    public AccessService(AccessRequestRepository accessRepository,
                         DeliveryOrderRepository orderRepository,
                         DeliveryEventService eventService,
                         CsService csService,
                         ColdChainMonitorService coldChainMonitorService) {
        this.accessRepository = accessRepository;
        this.orderRepository = orderRepository;
        this.eventService = eventService;
        this.csService = csService;
        this.coldChainMonitorService = coldChainMonitorService;
    }

    @Transactional
    public AccessRequest requestAccess(Long deliveryId, String gateName) {
        DeliveryOrder order = mustGet(deliveryId);
        if (order.getStatus() != DeliveryStatus.ASSIGNED) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许申请门禁（需先分配机器人）");
        }
        Community community = order.getCommunity();

        AccessRequest request = new AccessRequest();
        request.setDelivery(order);
        request.setRobotId(order.getRobot() == null ? null : order.getRobot().getId());
        request.setGateName(gateName);
        request.setStatus(AccessStatus.PENDING);
        accessRepository.save(request);

        order.setStatus(DeliveryStatus.ACCESS_PENDING);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order, request);
        eventService.emit(order.getId(), community.getId(),
                EventType.ACCESS_REQUESTED, EventActor.ROBOT, payload);
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "门禁等待，持续计算载物仓温度");
        }

        // 物业夜间限制运行：夜间时段自动拒绝
        if (community.isNightRestricted() && isNightNow(community)) {
            String reason = "物业夜间限制运行时段 " + community.getNightStart() + "-" + community.getNightEnd();
            request.setStatus(AccessStatus.DENIED);
            request.setReason(reason);
            request.setDecidedAt(LocalDateTime.now());
            request.setDecidedBy("SYSTEM");
            accessRepository.save(request);

            order.setStatus(DeliveryStatus.EXCEPTION);
            orderRepository.save(order);

            eventService.emit(order.getId(), community.getId(),
                    EventType.NIGHT_RESTRICTED, EventActor.SYSTEM, "NIGHT_LIMIT", null,
                    nightPayload(community, request));
            Map<String, Object> deniedPayload = basePayload(order, request);
            deniedPayload.put("reason", reason);
            eventService.emit(order.getId(), community.getId(),
                    EventType.ACCESS_DENIED, EventActor.PROPERTY, "NIGHT_LIMIT", null, deniedPayload);
            csService.openCase(order.getId(), CaseType.NIGHT_RESTRICTED, Responsibility.PROPERTY,
                    reason + "，机器人门口等待或改期配送", null);
        }
        return request;
    }

    @Transactional
    public AccessRequest approve(Long requestId, String decidedBy) {
        AccessRequest request = mustGetRequest(requestId);
        if (request.getStatus() != AccessStatus.PENDING) {
            throw ApiException.conflict("门禁申请已处理: " + request.getStatus());
        }
        request.setStatus(AccessStatus.APPROVED);
        request.setDecidedAt(LocalDateTime.now());
        request.setDecidedBy(decidedBy);
        accessRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        order.setStatus(DeliveryStatus.ENTERING);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ACCESS_GRANTED, EventActor.PROPERTY, basePayload(order, request));
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "门禁开通，累计门禁等待已计入温控");
        }
        return request;
    }

    @Transactional
    public AccessRequest deny(Long requestId, String reason, String decidedBy) {
        AccessRequest request = mustGetRequest(requestId);
        if (request.getStatus() != AccessStatus.PENDING) {
            throw ApiException.conflict("门禁申请已处理: " + request.getStatus());
        }
        request.setStatus(AccessStatus.DENIED);
        request.setReason(reason);
        request.setDecidedAt(LocalDateTime.now());
        request.setDecidedBy(decidedBy);
        accessRepository.save(request);

        DeliveryOrder order = request.getDelivery();
        order.setStatus(DeliveryStatus.EXCEPTION);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order, request);
        payload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ACCESS_DENIED, EventActor.PROPERTY, payload);
        csService.openCase(order.getId(), CaseType.ACCESS_DENIED, Responsibility.PROPERTY,
                "物业拒绝门禁: " + reason, null);
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(), "门禁被拒，累计门禁等待已计入温控");
        }
        return request;
    }

    /** 判断是否处于夜间限制时段（支持跨零点，如 22:00-07:00） */
    public boolean isNightNow(Community community) {
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(community.getNightStart());
        LocalTime end = LocalTime.parse(community.getNightEnd());
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private Map<String, Object> basePayload(DeliveryOrder order, AccessRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("orderNo", order.getOrderNo());
        payload.put("gateName", request.getGateName());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        return payload;
    }

    private Map<String, Object> nightPayload(Community community, AccessRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", request.getId());
        payload.put("communityName", community.getName());
        payload.put("nightStart", community.getNightStart());
        payload.put("nightEnd", community.getNightEnd());
        return payload;
    }

    private DeliveryOrder mustGet(Long deliveryId) {
        return orderRepository.findById(deliveryId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + deliveryId));
    }

    private AccessRequest mustGetRequest(Long requestId) {
        return accessRepository.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("门禁申请不存在: " + requestId));
    }
}
