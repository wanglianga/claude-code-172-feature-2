package com.example.robotdelivery.delivery;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.coldchain.ColdChainMonitorService;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.robot.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 配送主流程编排：分配机器人 -> 门禁 -> 电梯 -> 避障 -> 到门通知 -> 开仓签收，
 * 以及各类异常（低电量/仓门故障/超时未取/送错楼栋/人工接力/退回）。
 */
@Service
public class DeliveryService {

    private static final Set<DeliveryStatus> OBSTACLE_ALLOWED = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR);
    private static final Set<DeliveryStatus> RELAY_ALLOWED = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.EXCEPTION);
    private static final Set<DeliveryStatus> REDELIVER_ALLOWED = EnumSet.of(
            DeliveryStatus.EXCEPTION, DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT,
            DeliveryStatus.ON_FLOOR, DeliveryStatus.WAITING_PICKUP);
    private static final Set<DeliveryStatus> RETURN_ALLOWED = EnumSet.of(
            DeliveryStatus.ASSIGNED, DeliveryStatus.ACCESS_PENDING, DeliveryStatus.ENTERING,
            DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR, DeliveryStatus.WAITING_PICKUP,
            DeliveryStatus.COMPARTMENT_OPEN, DeliveryStatus.EXCEPTION);

    private final DeliveryOrderRepository orderRepository;
    private final RobotRepository robotRepository;
    private final CargoCompartmentRepository compartmentRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;
    private final NotificationService notificationService;
    private final CsService csService;
    private final DeliveryProperties properties;
    private final ColdChainMonitorService coldChainMonitorService;

    public DeliveryService(DeliveryOrderRepository orderRepository,
                           RobotRepository robotRepository,
                           CargoCompartmentRepository compartmentRepository,
                           BuildingRepository buildingRepository,
                           DeliveryEventService eventService,
                           NotificationService notificationService,
                           CsService csService,
                           DeliveryProperties properties,
                           ColdChainMonitorService coldChainMonitorService) {
        this.orderRepository = orderRepository;
        this.robotRepository = robotRepository;
        this.compartmentRepository = compartmentRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.csService = csService;
        this.properties = properties;
        this.coldChainMonitorService = coldChainMonitorService;
    }

    /** 分配机器人与载物仓（冷藏订单优先冷藏仓） */
    @Transactional
    public DeliveryOrder assignRobot(Long orderId, Long robotId) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.CREATED);
        Robot robot = robotRepository.findById(robotId)
                .orElseThrow(() -> ApiException.notFound("机器人不存在: " + robotId));
        if (!robot.getCommunity().getId().equals(order.getCommunity().getId())) {
            throw ApiException.badRequest("机器人不属于订单所在社区");
        }
        if (robot.getStatus() != RobotStatus.IDLE) {
            throw ApiException.conflict("机器人当前状态[" + robot.getStatus() + "]不可接单");
        }
        if (robot.getBattery() < properties.getLowBatteryThreshold()) {
            throw ApiException.conflict(
                    "机器人电量不足(" + robot.getBattery() + "%)，低于阈值" + properties.getLowBatteryThreshold() + "%");
        }
        CargoCompartment compartment = order.isColdChain()
                ? compartmentRepository.findFirstByRobotIdAndStatusAndColdChainCapableOrderByCompartmentNoAsc(
                        robotId, CompartmentStatus.IDLE, true)
                : compartmentRepository.findFirstByRobotIdAndStatusOrderByCompartmentNoAsc(
                        robotId, CompartmentStatus.IDLE);
        if (compartment == null) {
            throw ApiException.conflict("机器人无可用载物仓" + (order.isColdChain() ? "（需冷藏仓）" : ""));
        }

        compartment.setStatus(CompartmentStatus.OCCUPIED);
        compartmentRepository.save(compartment);
        robot.setStatus(RobotStatus.BUSY);
        robotRepository.save(robot);

        order.setRobot(robot);
        order.setCompartmentNo(compartment.getCompartmentNo());
        order.setStatus(DeliveryStatus.ASSIGNED);
        order.setAssignedAt(LocalDateTime.now());
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("robotCode", robot.getCode());
        payload.put("battery", robot.getBattery());
        payload.put("compartmentNo", compartment.getCompartmentNo());
        payload.put("coldChainCapable", compartment.isColdChainCapable());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROBOT_ASSIGNED, EventActor.OPS, payload);
        return order;
    }

    /** 避障停留上报：老人儿童挡路/行人/施工等；施工同时触发路线调整 */
    @Transactional
    public DeliveryOrder reportObstacle(Long orderId, ObstacleType obstacleType, Long waitSeconds, String location) {
        DeliveryOrder order = mustGet(orderId);
        if (!OBSTACLE_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报避障");
        }
        Map<String, Object> payload = basePayload(order);
        payload.put("obstacleType", obstacleType.name());
        payload.put("waitSeconds", waitSeconds);
        payload.put("location", location);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.OBSTACLE_WAIT, EventActor.ROBOT, obstacleType.name(), waitSeconds, payload);

        if (obstacleType == ObstacleType.CONSTRUCTION) {
            Map<String, Object> adjustPayload = basePayload(order);
            adjustPayload.put("detail", "楼道施工，机器人绕行备用路线");
            adjustPayload.put("location", location);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.ROUTE_ADJUSTED, EventActor.ROBOT, "CONSTRUCTION", waitSeconds, adjustPayload);
        }
        if (order.isColdChain()) {
            coldChainMonitorService.evaluateOrder(order.getId(),
                    "避障等待(" + obstacleType.name() + ")，持续计算载物仓温度");
        }
        return order;
    }

    /** 到达用户门口：短信 + 小程序通知用户取件 */
    @Transactional
    public DeliveryOrder arriveDoor(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getStatus() != DeliveryStatus.ENTERING && order.getStatus() != DeliveryStatus.ON_FLOOR) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报到门（需先进入楼栋/到达楼层）");
        }
        order.setStatus(DeliveryStatus.WAITING_PICKUP);
        order.setNotifiedAt(LocalDateTime.now());
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ARRIVED_AT_DOOR, EventActor.ROBOT, basePayload(order));
        notificationService.notifyArrival(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("channels", "SMS,MINI_PROGRAM");
        payload.put("userPhone", order.getUserPhone());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, payload);
        return order;
    }

    /** 用户通过小程序/按键打开载物仓 */
    @Transactional
    public DeliveryOrder openCompartment(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.WAITING_PICKUP);
        order.setStatus(DeliveryStatus.COMPARTMENT_OPEN);
        orderRepository.save(order);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COMPARTMENT_OPENED, EventActor.USER, basePayload(order));
        return order;
    }

    /** 载物仓打不开：设备故障，触发客服介入 */
    @Transactional
    public DeliveryOrder reportCompartmentFault(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getStatus() != DeliveryStatus.WAITING_PICKUP
                && order.getStatus() != DeliveryStatus.COMPARTMENT_OPEN) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报仓门故障");
        }
        order.setStatus(DeliveryStatus.EXCEPTION);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COMPARTMENT_OPEN_FAILED, EventActor.ROBOT, basePayload(order));
        csService.openCase(orderId, CaseType.COMPARTMENT_FAULT, Responsibility.ROBOT_VENDOR,
                "载物仓" + order.getCompartmentNo() + "号仓打不开，需现场处理或人工接力", null);
        return order;
    }

    /** 用户签收 */
    @Transactional
    public DeliveryOrder sign(Long orderId, String signBy) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.COMPARTMENT_OPEN);
        order.setStatus(DeliveryStatus.SIGNED);
        order.setSignedAt(LocalDateTime.now());
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("signBy", signBy);
        payload.put("durationSeconds", Duration.between(order.getCreatedAt(), order.getSignedAt()).getSeconds());
        payload.put("coldChainTimedOut", order.isColdChainTimedOut());
        payload.put("onTime", order.getEstimatedDeliveryAt() == null
                || !order.getSignedAt().isAfter(order.getEstimatedDeliveryAt()));
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.SIGNED, EventActor.USER, payload);
        return order;
    }

    /** 开始退回（如用户超时未取、门禁长期被拒后的主动退回） */
    @Transactional
    public DeliveryOrder startReturn(Long orderId, String reason) {
        DeliveryOrder order = mustGet(orderId);
        if (!RETURN_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许退回");
        }
        order.setStatus(DeliveryStatus.RETURNING);
        order.setReturnReason(reason);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RETURN_STARTED, EventActor.ROBOT, payload);
        return order;
    }

    /** 确认退回完成，释放机器人与载物仓 */
    @Transactional
    public DeliveryOrder confirmReturn(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.RETURNING);
        order.setStatus(DeliveryStatus.RETURNED);
        order.setReturnedAt(LocalDateTime.now());
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", order.getReturnReason());
        payload.put("durationSeconds", Duration.between(order.getCreatedAt(), order.getReturnedAt()).getSeconds());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RETURNED, EventActor.ROBOT, payload);
        return order;
    }

    /** 人工接力：机器人无法继续配送时交由人工配送员完成 */
    @Transactional
    public DeliveryOrder manualRelay(Long orderId, String courierName) {
        DeliveryOrder order = mustGet(orderId);
        if (!RELAY_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许人工接力");
        }
        order.setRelayCourier(courierName);
        if (order.getStatus() == DeliveryStatus.EXCEPTION) {
            order.setStatus(DeliveryStatus.ENTERING);
        }
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("courier", courierName);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.MANUAL_RELAY, EventActor.OPS, payload);
        return order;
    }

    /** 送错楼栋后的改派：调整收货楼栋并重新进楼配送 */
    @Transactional
    public DeliveryOrder redeliver(Long orderId, Long newBuildingId) {
        DeliveryOrder order = mustGet(orderId);
        if (!REDELIVER_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许改派楼栋");
        }
        Building newBuilding = buildingRepository.findById(newBuildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + newBuildingId));
        if (!newBuilding.getCommunity().getId().equals(order.getCommunity().getId())) {
            throw ApiException.badRequest("新楼栋不属于订单所在社区");
        }
        String fromBuilding = order.getBuilding().getName();
        order.setBuilding(newBuilding);
        order.setStatus(DeliveryStatus.ENTERING);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("fromBuilding", fromBuilding);
        payload.put("toBuilding", newBuilding.getName());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROUTE_ADJUSTED, EventActor.OPS, "WRONG_BUILDING", null, payload);
        return order;
    }

    /** 取消配送 */
    @Transactional
    public DeliveryOrder cancel(Long orderId, String reason) {
        DeliveryOrder order = mustGet(orderId);
        if (DeliveryStatus.TERMINAL.contains(order.getStatus())) {
            throw ApiException.conflict("订单已终结: " + order.getStatus());
        }
        order.setStatus(DeliveryStatus.CANCELLED);
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.DELIVERY_CANCELLED, EventActor.OPS, payload);
        return order;
    }

    /** 释放机器人与载物仓（若机器人无其他在途订单则置为空闲） */
    private void releaseRobotAndCompartment(DeliveryOrder order) {
        Robot robot = order.getRobot();
        if (robot == null) {
            return;
        }
        if (order.getCompartmentNo() != null) {
            compartmentRepository.findByRobotIdAndCompartmentNo(robot.getId(), order.getCompartmentNo())
                    .ifPresent(c -> {
                        if (c.getStatus() == CompartmentStatus.OCCUPIED) {
                            c.setStatus(CompartmentStatus.IDLE);
                            compartmentRepository.save(c);
                        }
                    });
        }
        if (robot.getStatus() == RobotStatus.BUSY) {
            robot.setStatus(RobotStatus.IDLE);
            robotRepository.save(robot);
        }
    }

    private Map<String, Object> basePayload(DeliveryOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("roomNo", order.getRoomNo());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("compartmentNo", order.getCompartmentNo());
        if (order.getRelayCourier() != null) {
            payload.put("relayCourier", order.getRelayCourier());
        }
        return payload;
    }

    private void requireStatus(DeliveryOrder order, DeliveryStatus expected) {
        if (order.getStatus() != expected) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许该操作（需要状态: " + expected + "）");
        }
    }

    private DeliveryOrder mustGet(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + orderId));
    }
}
