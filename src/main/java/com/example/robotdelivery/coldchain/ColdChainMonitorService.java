package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.access.AccessRequest;
import com.example.robotdelivery.access.AccessRequestRepository;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsCaseRepository;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.ColdWarnLevel;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冷链监控核心：配送药品或生鲜时，服务持续计算载物仓温度与剩余可配送时长。
 * 温度模型：等待门禁 / 电梯 / 避障时载物仓按速率升温，机器人移动制冷时回冷；
 * 同时支持设备实测温度上报。存在超时风险时提前通知客服与用户，
 * 升级为严重风险时开立客服工单，必要时由处置流程退回站点更换商品。
 *
 * 每次等待门禁 / 电梯 / 避障与周期巡检都会产生温度快照，累计等待秒数与
 * 机器人路线调整证据一并保留，供冷链超时损失关联与赔付判断。
 */
@Slf4j
@Service
public class ColdChainMonitorService {

    /** 仍需冷链监控的在途状态（已分配机器人起监控，退回中之后不再预警） */
    private static final Set<DeliveryStatus> MONITORED = EnumSet.of(
            DeliveryStatus.ASSIGNED, DeliveryStatus.ACCESS_PENDING,
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.COMPARTMENT_OPEN, DeliveryStatus.EXCEPTION);

    private final DeliveryOrderRepository orderRepository;
    private final AccessRequestRepository accessRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryEventRepository eventRepository;
    private final ColdChainSnapshotRepository snapshotRepository;
    private final CsCaseRepository csCaseRepository;
    private final CsService csService;
    private final NotificationService notificationService;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;

    /** 仅用于 TEMP_CALCULATED 事件的节流与实测温度暂存（重启后自动重建，不影响业务） */
    private final Map<Long, TrackState> tracks = new HashMap<>();

    public ColdChainMonitorService(DeliveryOrderRepository orderRepository,
                                   AccessRequestRepository accessRepository,
                                   ElevatorRequestRepository elevatorRepository,
                                   DeliveryEventRepository eventRepository,
                                   ColdChainSnapshotRepository snapshotRepository,
                                   CsCaseRepository csCaseRepository,
                                   CsService csService,
                                   NotificationService notificationService,
                                   DeliveryEventService eventService,
                                   DeliveryProperties properties) {
        this.orderRepository = orderRepository;
        this.accessRepository = accessRepository;
        this.elevatorRepository = elevatorRepository;
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.csCaseRepository = csCaseRepository;
        this.csService = csService;
        this.notificationService = notificationService;
        this.eventService = eventService;
        this.properties = properties;
    }

    /** 等待归因统计（同时用于温度计算与损失评估） */
    public record WaitStats(long accessWaitSeconds, long elevatorWaitSeconds, long obstacleWaitSeconds,
                            long movingSeconds, long elapsedSeconds, int routeAdjustments,
                            String routeEvidence) {
        public long totalWaitSeconds() {
            return accessWaitSeconds + elevatorWaitSeconds + obstacleWaitSeconds;
        }
    }

    /** 周期巡检入口：对全部在途冷链单持续计算温度与剩余时长 */
    @Transactional
    public int monitorActiveOrders() {
        if (!properties.isSchedulerEnabled()) {
            return 0;
        }
        List<DeliveryOrder> orders = orderRepository.findByColdChainTrueAndStatusIn(MONITORED);
        int evaluated = 0;
        for (DeliveryOrder order : orders) {
            try {
                evaluateOrder(order, null);
                evaluated++;
            } catch (Exception e) {
                log.error("冷链监控计算失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        tracks.keySet().removeIf(id -> orderRepository.findById(id)
                .map(o -> !MONITORED.contains(o.getStatus())).orElse(true));
        return evaluated;
    }

    /**
     * 设备实测温度上报（载物仓温度传感器），随后立即重新评估风险。
     */
    @Transactional
    public ColdChainSnapshot reportTemperature(Long orderId, double tempCelsius, String note) {
        DeliveryOrder order = mustGet(orderId);
        if (!order.isColdChain()) {
            throw com.example.robotdelivery.config.ApiException.badRequest("非冷链订单无需温度上报");
        }
        tracks.computeIfAbsent(orderId, k -> new TrackState()).sensorTemp = tempCelsius;
        return evaluateOrder(order, note == null ? "设备温度上报" : note);
    }

    /**
     * 持续计算单个订单的载物仓温度、剩余可配送时长并落快照；风险升级时通知与开单。
     */
    @Transactional
    public ColdChainSnapshot evaluateOrder(Long orderId, String note) {
        return evaluateOrder(mustGet(orderId), note);
    }

    private ColdChainSnapshot evaluateOrder(DeliveryOrder order, String note) {
        if (!order.isColdChain() || !MONITORED.contains(order.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        WaitStats stats = computeStats(order, now);

        double waitMinutes = stats.totalWaitSeconds() / 60.0;
        double movingMinutes = stats.movingSeconds() / 60.0;
        double modelTemp = properties.getColdchainBaseTempCelsius()
                + properties.getColdchainWarmRatePerMinute() * waitMinutes
                - properties.getColdchainCoolRatePerMinute() * movingMinutes;
        modelTemp = Math.max(properties.getColdchainBaseTempCelsius(), modelTemp);

        TrackState state = tracks.computeIfAbsent(order.getId(), k -> new TrackState());
        double temp = state.sensorTemp == null ? modelTemp : Math.max(modelTemp, state.sensorTemp);
        temp = round1(temp);

        long remaining = order.getColdChainDeadline() == null ? 0
                : Math.max(0, Duration.between(now, order.getColdChainDeadline()).getSeconds());
        double remainingRatio = properties.getColdchainTimeoutSeconds() <= 0 ? 1
                : (double) remaining / properties.getColdchainTimeoutSeconds();

        ColdWarnLevel level = ColdWarnLevel.NONE;
        if (temp >= properties.getColdchainWarnTempCelsius()
                || remainingRatio <= properties.getColdchainWarnRemainingRatio()) {
            level = ColdWarnLevel.WARN;
        }
        if (temp >= properties.getColdchainCriticalTempCelsius()
                || remainingRatio <= properties.getColdchainCriticalRemainingRatio()) {
            level = ColdWarnLevel.CRITICAL;
        }

        String riskFactor = dominantFactor(stats, temp);
        ColdChainSnapshot snapshot = new ColdChainSnapshot();
        snapshot.setDelivery(order);
        snapshot.setRobotId(order.getRobot() == null ? null : order.getRobot().getId());
        snapshot.setCompartmentNo(order.getCompartmentNo());
        snapshot.setTempCelsius(BigDecimal.valueOf(temp).setScale(2, RoundingMode.HALF_UP));
        snapshot.setRemainingSeconds(remaining);
        snapshot.setWarnLevel(level);
        snapshot.setAccessWaitSeconds(stats.accessWaitSeconds());
        snapshot.setElevatorWaitSeconds(stats.elevatorWaitSeconds());
        snapshot.setObstacleWaitSeconds(stats.obstacleWaitSeconds());
        snapshot.setMovingSeconds(stats.movingSeconds());
        snapshot.setRiskFactor(riskFactor);
        snapshot.setPhase(order.getStatus().name());
        snapshot.setNote(note);
        snapshotRepository.save(snapshot);
        state.snapshotCount++;

        order.setLastTempCelsius(snapshot.getTempCelsius());
        order.setRemainingSeconds(remaining);
        order.setLastCalcAt(now);

        handleLevelTransition(order, level, temp, remaining, riskFactor, stats, state, now);
        orderRepository.save(order);

        maybeEmitTempCalculated(order, snapshot, stats, state);
        return snapshot;
    }

    /** 风险升级：WARN 提前通知客服与用户；CRITICAL 再通知并自动开客服工单 */
    private void handleLevelTransition(DeliveryOrder order, ColdWarnLevel newLevel, double temp,
                                       long remaining, String riskFactor, WaitStats stats,
                                       TrackState state, LocalDateTime now) {
        ColdWarnLevel current = order.getColdChainWarnLevel() == null ? ColdWarnLevel.NONE
                : order.getColdChainWarnLevel();
        if (newLevel == ColdWarnLevel.NONE || newLevel.ordinal() <= current.ordinal()) {
            if (order.getColdChainWarnLevel() == null) {
                order.setColdChainWarnLevel(ColdWarnLevel.NONE);
            }
            return;
        }

        Map<String, Object> payload = riskPayload(order, temp, remaining, riskFactor, stats);
        if (newLevel == ColdWarnLevel.WARN) {
            order.setColdChainWarnLevel(ColdWarnLevel.WARN);
            order.setColdChainWarnedAt(now);
            notificationService.notifyColdChainRisk(order, false, temp, remaining, riskFactor);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_WARNING, EventActor.SYSTEM, riskFactor, stats.totalWaitSeconds(), payload);
            log.info("冷链超时风险预警: orderNo={} temp={}℃ remaining={}s factor={}",
                    order.getOrderNo(), temp, remaining, riskFactor);
        } else {
            // CRITICAL（无论之前是否 WARN 都补一次升级通知）
            if (current == ColdWarnLevel.NONE) {
                order.setColdChainWarnedAt(now);
            }
            order.setColdChainWarnLevel(ColdWarnLevel.CRITICAL);
            order.setColdChainCriticalAt(now);
            notificationService.notifyColdChainRisk(order, true, temp, remaining, riskFactor);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_WARNING, EventActor.SYSTEM, riskFactor, stats.totalWaitSeconds(), payload);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_CRITICAL, EventActor.SYSTEM, riskFactor, stats.totalWaitSeconds(), payload);
            openRiskCaseIfAbsent(order, temp, remaining, riskFactor, stats);
            log.warn("冷链严重风险，建议退回站点更换商品: orderNo={} temp={}℃ remaining={}s factor={}",
                    order.getOrderNo(), temp, remaining, riskFactor);
        }
    }

    /** 严重风险自动客服介入（同一配送单同类未结工单去重） */
    private void openRiskCaseIfAbsent(DeliveryOrder order, double temp, long remaining,
                                      String riskFactor, WaitStats stats) {
        boolean exists = csCaseRepository.findByDeliveryId(order.getId()).stream()
                .anyMatch(c -> c.getCaseType() == CaseType.COLDCHAIN_RISK
                        && c.getStatus() == com.example.robotdelivery.cs.CaseStatus.OPEN);
        if (exists) {
            return;
        }
        String note = String.format(
                "冷藏订单超时严重风险：载物仓%.1f℃，剩余可配送%d秒；累计等待 门禁%ds/电梯%ds/避障%ds，"
                        + "路线调整%d次（%s），建议立即协调放行/电梯，否则退回站点更换商品。",
                temp, remaining, stats.accessWaitSeconds(), stats.elevatorWaitSeconds(),
                stats.obstacleWaitSeconds(), stats.routeAdjustments(),
                NotificationService.riskFactorCn(riskFactor));
        csService.openCase(order.getId(), CaseType.COLDCHAIN_RISK, Responsibility.NONE, note, null);
    }

    /**
     * 汇总门禁等待、电梯等待、避障（路线）等待、移动时长与路线调整证据。
     */
    public WaitStats computeStats(DeliveryOrder order, LocalDateTime now) {
        long accessWait = 0;
        for (AccessRequest r : accessRepository.findByDeliveryId(order.getId())) {
            LocalDateTime end = r.getDecidedAt() != null ? r.getDecidedAt()
                    : r.getStatus() == com.example.robotdelivery.access.AccessStatus.PENDING ? now : null;
            if (end != null) {
                accessWait += Math.max(0, Duration.between(r.getRequestedAt(), end).getSeconds());
            }
        }

        long elevatorWait = 0;
        List<ElevatorRequest> elevators = elevatorRepository.findByDeliveryIdOrderByIdDesc(order.getId());
        for (ElevatorRequest r : elevators) {
            LocalDateTime end = r.getResolvedAt() != null ? r.getResolvedAt()
                    : r.getStatus() == com.example.robotdelivery.elevator.ElevatorStatus.PENDING ? now : null;
            if (end != null) {
                elevatorWait += Math.max(0, Duration.between(r.getRequestedAt(), end).getSeconds());
            }
        }

        List<DeliveryEvent> events = eventRepository.findByDeliveryIdOrderByOccurredAtAsc(order.getId());
        long obstacleWait = events.stream()
                .filter(e -> EventType.OBSTACLE_WAIT.name().equals(e.getEventType()))
                .mapToLong(e -> e.getWaitSeconds() == null ? 0L : e.getWaitSeconds())
                .sum();
        List<DeliveryEvent> routes = events.stream()
                .filter(e -> EventType.ROUTE_ADJUSTED.name().equals(e.getEventType()))
                .toList();
        String routeEvidence = routes.stream()
                .map(e -> e.getOccurredAt() + (e.getReason() == null ? "" : "(" + e.getReason() + ")"))
                .reduce((a, b) -> a + " -> " + b)
                .orElse("无路线调整");

        LocalDateTime start = order.getAssignedAt() != null ? order.getAssignedAt() : order.getCreatedAt();
        long elapsed = Math.max(0, Duration.between(start, now).getSeconds());
        long moving = Math.max(0, elapsed - accessWait - elevatorWait - obstacleWait);
        return new WaitStats(accessWait, elevatorWait, obstacleWait, moving, elapsed,
                routes.size(), routeEvidence);
    }

    /** 冷链监控视图：最新温度/剩余时长/预警级别/等待归因/证据快照 */
    public Map<String, Object> monitoringView(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        WaitStats stats = computeStats(order, LocalDateTime.now());
        List<ColdChainSnapshot> snapshots = snapshotRepository.findByDelivery_IdOrderByCreatedAtAsc(orderId);
        ColdChainSnapshot latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deliveryId", order.getId());
        view.put("orderNo", order.getOrderNo());
        view.put("orderType", order.getOrderType().name());
        view.put("coldChain", order.isColdChain());
        view.put("status", order.getStatus().name());
        view.put("coldChainDeadline", order.getColdChainDeadline() == null ? null : order.getColdChainDeadline().toString());
        view.put("warnLevel", order.getColdChainWarnLevel() == null ? ColdWarnLevel.NONE.name()
                : order.getColdChainWarnLevel().name());
        view.put("warnedAt", order.getColdChainWarnedAt() == null ? null : order.getColdChainWarnedAt().toString());
        view.put("criticalAt", order.getColdChainCriticalAt() == null ? null : order.getColdChainCriticalAt().toString());
        view.put("lastTempCelsius", order.getLastTempCelsius());
        view.put("remainingSeconds", order.getRemainingSeconds());
        view.put("lastCalcAt", order.getLastCalcAt() == null ? null : order.getLastCalcAt().toString());
        view.put("compartmentNo", order.getCompartmentNo());
        view.put("accessWaitSeconds", stats.accessWaitSeconds());
        view.put("elevatorWaitSeconds", stats.elevatorWaitSeconds());
        view.put("obstacleWaitSeconds", stats.obstacleWaitSeconds());
        view.put("movingSeconds", stats.movingSeconds());
        view.put("routeAdjustmentCount", stats.routeAdjustments());
        view.put("routeEvidence", stats.routeEvidence());
        view.put("snapshotCount", snapshots.size());
        view.put("latestSnapshot", latest);
        return view;
    }

    private String dominantFactor(WaitStats stats, double temp) {
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

    private Map<String, Object> riskPayload(DeliveryOrder order, double temp, long remaining,
                                            String riskFactor, WaitStats stats) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("compartmentNo", order.getCompartmentNo());
        payload.put("tempCelsius", temp);
        payload.put("remainingSeconds", remaining);
        payload.put("coldChainDeadline",
                order.getColdChainDeadline() == null ? null : order.getColdChainDeadline().toString());
        payload.put("riskFactor", riskFactor);
        payload.put("riskFactorCn", NotificationService.riskFactorCn(riskFactor));
        payload.put("accessWaitSeconds", stats.accessWaitSeconds());
        payload.put("elevatorWaitSeconds", stats.elevatorWaitSeconds());
        payload.put("obstacleWaitSeconds", stats.obstacleWaitSeconds());
        payload.put("routeAdjustmentCount", stats.routeAdjustments());
        return payload;
    }

    /** 温度持续计算事件节流：首次、级别变化、温度明显变化或每 6 个快照上报一次 */
    private void maybeEmitTempCalculated(DeliveryOrder order, ColdChainSnapshot snapshot,
                                         WaitStats stats, TrackState state) {
        boolean should = state.snapshotCount == 1
                || state.lastEmittedLevel != snapshot.getWarnLevel()
                || state.lastEmittedTemp == null
                || Math.abs(state.lastEmittedTemp - snapshot.getTempCelsius().doubleValue()) >= 0.5
                || state.snapshotCount % 6 == 0;
        if (!should) {
            return;
        }
        Map<String, Object> payload = riskPayload(order, snapshot.getTempCelsius().doubleValue(),
                snapshot.getRemainingSeconds(), snapshot.getRiskFactor() == null ? "COMPARTMENT_WARMING"
                        : snapshot.getRiskFactor(), stats);
        payload.put("snapshotId", snapshot.getId());
        payload.put("phase", snapshot.getPhase());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.TEMP_CALCULATED, EventActor.ROBOT, payload);
        state.lastEmittedTemp = snapshot.getTempCelsius().doubleValue();
        state.lastEmittedLevel = snapshot.getWarnLevel();
    }

    /** 温度快照时间线 */
    public List<ColdChainSnapshot> snapshots(Long orderId) {
        return snapshotRepository.findByDelivery_IdOrderByCreatedAtAsc(orderId);
    }

    private double round1(double v) {        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private DeliveryOrder mustGet(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> com.example.robotdelivery.config.ApiException
                        .notFound("配送订单不存在: " + orderId));
    }

    /** 清理在途跟踪（订单终结/退回时调用） */
    public void clearTrack(Long orderId) {
        tracks.remove(orderId);
    }

    private static class TrackState {
        int snapshotCount;
        Double lastEmittedTemp;
        ColdWarnLevel lastEmittedLevel;
        Double sensorTemp;
    }
}
