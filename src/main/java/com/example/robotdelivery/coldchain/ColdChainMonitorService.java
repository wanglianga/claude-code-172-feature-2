package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.access.AccessRequest;
import com.example.robotdelivery.access.AccessRequestRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.delivery.DeliveryService;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorRequestRepository;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.order.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冷藏订单超时预警核心服务。
 *
 * <p>配送药品 / 生鲜途中持续计算载物仓温度与剩余可配送时长；温度模型为“安全温度 + 等待附加温升”，
 * 门禁、电梯、避障、路线调整造成的等待都会推高仓温并按类归因。剩余时长不足或温度越限时：
 * <ul>
 *   <li>WARNING：提前通知客服与用户；</li>
 *   <li>CRITICAL：开立客服工单，必要时自动退回站点更换商品，原配送证据封存供赔付判断；</li>
 *   <li>退回站点后生成“重新配送 / 退款”选项：重新配送通知门店重新备货并生成新配送单，
 *       退款则通知用户并结案。</li>
 * </ul>
 */
@Slf4j
@Service
public class ColdChainMonitorService {

    /** 需要持续计算温度/剩余时长的冷藏在途状态 */
    private static final Set<DeliveryStatus> MONITORED = EnumSet.of(
            DeliveryStatus.ASSIGNED, DeliveryStatus.ACCESS_PENDING, DeliveryStatus.ENTERING,
            DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR, DeliveryStatus.WAITING_PICKUP,
            DeliveryStatus.COMPARTMENT_OPEN, DeliveryStatus.EXCEPTION);

    private final DeliveryOrderRepository orderRepository;
    private final ColdChainSnapshotRepository snapshotRepository;
    private final ColdChainResolutionRepository resolutionRepository;
    private final AccessRequestRepository accessRepository;
    private final ElevatorRequestRepository elevatorRepository;
    private final DeliveryEventRepository eventRepository;
    private final DeliveryEventService eventService;
    private final NotificationService notificationService;
    private final CsService csService;
    private final DeliveryService deliveryService;
    private final OrderService orderService;
    private final DeliveryProperties properties;

    public ColdChainMonitorService(DeliveryOrderRepository orderRepository,
                                   ColdChainSnapshotRepository snapshotRepository,
                                   ColdChainResolutionRepository resolutionRepository,
                                   AccessRequestRepository accessRepository,
                                   ElevatorRequestRepository elevatorRepository,
                                   DeliveryEventRepository eventRepository,
                                   DeliveryEventService eventService,
                                   NotificationService notificationService,
                                   CsService csService,
                                   DeliveryService deliveryService,
                                   OrderService orderService,
                                   DeliveryProperties properties) {
        this.orderRepository = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.resolutionRepository = resolutionRepository;
        this.accessRepository = accessRepository;
        this.elevatorRepository = elevatorRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.csService = csService;
        this.deliveryService = deliveryService;
        this.orderService = orderService;
        this.properties = properties;
    }

    /** 巡检入口：对所有冷藏在途单持续计算温度与剩余可配送时长 */
    @Transactional
    public void evaluateAll() {
        List<DeliveryOrder> orders = orderRepository.findByColdChainTrueAndStatusIn(MONITORED);
        for (DeliveryOrder order : orders) {
            try {
                if (order.getColdChainDeadline() == null) {
                    continue;
                }
                evaluateOrder(order, false, null);
            } catch (Exception e) {
                log.error("冷藏监控计算失败: orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    /**
     * 计算单票冷藏单的仓温、剩余时长与风险等级，落一条快照，并在等级跃迁时预警/退回。
     *
     * @param forceReturn  是否强制按严重风险退回（客服/运营人工触发）
     * @param manualReason 人工退回原因（可空）
     */
    @Transactional
    public Map<String, Object> evaluateOrder(DeliveryOrder order, boolean forceReturn, String manualReason) {
        LocalDateTime now = LocalDateTime.now();
        WaitAttribution attr = attributeWaits(order, now);
        long remaining = order.getColdChainDeadline() == null
                ? Long.MAX_VALUE
                : Duration.between(now, order.getColdChainDeadline()).getSeconds();

        double computedTemp = properties.getColdchainTempSafeC()
                + attr.totalWaitSeconds() / 60.0 * properties.getColdchainTempRisePerWaitMinute();
        double reportedTemp = order.getColdChainLastTemp() == null
                ? Double.NEGATIVE_INFINITY : order.getColdChainLastTemp().doubleValue();
        double temperature = Math.max(computedTemp, reportedTemp);

        TemperatureRiskLevel level = resolveLevel(temperature, remaining, forceReturn);
        String riskReason = forceReturn && manualReason != null && !manualReason.isBlank()
                ? "MANUAL" : resolveRiskReason(temperature, remaining, attr, reportedTemp);
        long snapshotCount = snapshotRepository.findByDeliveryIdOrderByCreatedAtAsc(order.getId()).size();
        saveSnapshot(order, temperature, remaining, level, attr, riskReason, snapshotCount);
        order.setColdChainLastTemp(BigDecimal.valueOf(temperature).setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("temperatureC", round(temperature));
        result.put("remainingSeconds", remaining);
        result.put("riskLevel", level.name());
        result.put("riskReason", riskReason);
        result.put("waits", waitsMap(attr));

        if (level == TemperatureRiskLevel.CRITICAL && order.getColdChainCriticalAt() == null) {
            if (order.getColdChainWarnedAt() == null) {
                // 首次即严重风险：记录预警时点，但不再重复发送 WARNING 通知
                order.setColdChainWarnedAt(LocalDateTime.now());
            }
            onCritical(order, temperature, remaining, attr, riskReason, manualReason);
        } else if (level == TemperatureRiskLevel.WARNING
                && order.getColdChainCriticalAt() == null && order.getColdChainWarnedAt() == null) {
            onWarning(order, temperature, remaining, attr, riskReason);
        }
        orderRepository.save(order);
        return result;
    }

    /** 设备上报载物仓温度后立即重新计算（持续计算以服务端模型为准，取与上报值的较高者） */
    @Transactional
    public Map<String, Object> reportTemperature(Long orderId, Double temperatureC) {
        DeliveryOrder order = mustGetColdChain(orderId);
        if (temperatureC == null || temperatureC < -30 || temperatureC > 40) {
            throw ApiException.badRequest("温度值不合法（-30~40℃）");
        }
        order.setColdChainLastTemp(BigDecimal.valueOf(temperatureC).setScale(2, RoundingMode.HALF_UP));
        orderRepository.save(order);
        return evaluateOrder(order, false, null);
    }

    /** 客服/运营在必要时人工判定退回站点更换商品 */
    @Transactional
    public Map<String, Object> manualRiskReturn(Long orderId, String reason) {
        DeliveryOrder order = mustGetColdChain(orderId);
        if (!MONITORED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许冷藏风险退回");
        }
        return evaluateOrder(order, true, reason);
    }

    private TemperatureRiskLevel resolveLevel(double temp, long remaining, boolean forceReturn) {
        if (forceReturn || remaining <= properties.getColdchainCriticalLeadSeconds()
                || temp >= properties.getColdchainTempCriticalC()) {
            return TemperatureRiskLevel.CRITICAL;
        }
        if (remaining <= properties.getColdchainWarnLeadSeconds()
                || temp >= properties.getColdchainTempWarnC()) {
            return TemperatureRiskLevel.WARNING;
        }
        return TemperatureRiskLevel.NORMAL;
    }

    private String resolveRiskReason(double temp, long remaining, WaitAttribution attr, double reportedTemp) {
        boolean deadlineRisk = remaining <= properties.getColdchainWarnLeadSeconds();
        boolean tempRisk = temp >= properties.getColdchainTempWarnC();
        // 时间紧迫且已有可归因等待（门禁/电梯/避障/路线），优先归因到主导等待来源
        if (deadlineRisk && attr.totalWaitSeconds() > 0 && !"NONE".equals(attr.dominantReason())) {
            return attr.dominantReason();
        }
        if (deadlineRisk) {
            return "DEADLINE";
        }
        if (tempRisk) {
            if (attr.totalWaitSeconds() <= 0 && reportedTemp != Double.NEGATIVE_INFINITY) {
                return "TEMPERATURE";
            }
            return attr.dominantReason();
        }
        return "NONE";
    }

    /** WARNING：提前通知客服与用户 */
    private void onWarning(DeliveryOrder order, double temp, long remaining,
                           WaitAttribution attr, String riskReason) {
        order.setColdChainWarnedAt(LocalDateTime.now());
        Map<String, Object> payload = riskPayload(order, temp, remaining, attr, riskReason);
        payload.put("alertLevel", "WARNING");
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COLDCHAIN_WARNING, EventActor.SYSTEM, riskReason, attr.totalWaitSeconds(), payload);
        notificationService.notifyColdChainRisk(order, false, temp, remaining,
                reasonCn(riskReason), describeWaits(attr));
        log.info("冷藏超时风险提前预警: orderNo={} temp={}℃ remain={}s reason={}",
                order.getOrderNo(), round(temp), remaining, riskReason);
    }

    /** CRITICAL：通知 + 客服工单 + 必要时退回站点、封存证据、生成处置选项 */
    private void onCritical(DeliveryOrder order, double temp, long remaining,
                            WaitAttribution attr, String riskReason, String manualReason) {
        order.setColdChainCriticalAt(LocalDateTime.now());

        Map<String, Object> criticalPayload = riskPayload(order, temp, remaining, attr, riskReason);
        criticalPayload.put("alertLevel", "CRITICAL");
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COLDCHAIN_WARNING, EventActor.SYSTEM, riskReason, attr.totalWaitSeconds(), criticalPayload);
        notificationService.notifyColdChainRisk(order, true, temp, remaining,
                reasonCn(riskReason), describeWaits(attr));

        String caseReason = manualReason != null && !manualReason.isBlank() ? manualReason : reasonCn(riskReason);
        csService.openCase(order.getId(), CaseType.COLDCHAIN_RISK, Responsibility.NONE,
                String.format("冷藏严重风险退回：%s；仓温%.1f℃，剩余%d秒；等待归因 门禁=%ds 电梯=%ds 避障=%ds 路线=%ds，待赔付判责",
                        caseReason, temp, remaining, attr.accessWaitSeconds(), attr.elevatorWaitSeconds(),
                        attr.obstacleWaitSeconds(), attr.routeWaitSeconds()), null);

        if (properties.isColdchainAutoReturnEnabled()) {
            returnToStation(order, temp, remaining, attr, riskReason, caseReason);
        }
    }

    /** 退回站点更换商品：原配送证据保留，随后生成重新配送/退款选项 */
    public void returnToStation(DeliveryOrder order, double temp, long remaining,
                                WaitAttribution attr, String riskReason, String reasonText) {
        String reason = String.format("冷藏超时风险(%s)，仓温%.1f℃，剩余%d秒，退回站点更换商品",
                reasonText, temp, remaining);
        deliveryService.startReturn(order.getId(), reason);
        deliveryService.confirmReturn(order.getId());

        order = orderRepository.findById(order.getId()).orElse(order);
        order.setColdChainReturnedRisk(true);
        orderRepository.save(order);

        Map<String, Object> returnPayload = riskPayload(order, temp, remaining, attr, riskReason);
        returnPayload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COLDCHAIN_RISK_RETURN, EventActor.ROBOT, riskReason, attr.totalWaitSeconds(), returnPayload);

        lockEvidence(order, temp, remaining, attr, riskReason);
        createResolutionOptions(order, temp, remaining, attr, riskReason);
    }

    /** 封存原配送证据（事件时间线 + 温度快照 + 等待归因），供赔付判断 */
    @Transactional
    public Map<String, Object> lockEvidence(DeliveryOrder order, double temp, long remaining,
                                            WaitAttribution attr, String riskReason) {
        order.setColdChainEvidenceLocked(true);
        orderRepository.save(order);

        Map<String, Object> evidence = buildEvidence(order, attr, temp, remaining, riskReason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COLDCHAIN_EVIDENCE_LOCKED, EventActor.SYSTEM, riskReason, attr.totalWaitSeconds(), evidence);
        log.info("冷藏原配送证据已封存: orderNo={}", order.getOrderNo());
        return evidence;
    }

    /** 退回站点后生成重新配送 / 退款选项 */
    private void createResolutionOptions(DeliveryOrder order, double temp, long remaining,
                                         WaitAttribution attr, String riskReason) {
        ColdChainResolution redeliver = newResolution(order, ResolutionOption.REDELIVER);
        ColdChainResolution refund = newResolution(order, ResolutionOption.REFUND);

        Map<String, Object> payload = riskPayload(order, temp, remaining, attr, riskReason);
        payload.put("options", List.of("REDELIVER", "REFUND"));
        payload.put("restockRequiredOnRedeliver", true);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COLDCHAIN_OPTION_CREATED, EventActor.SYSTEM, payload);

        // 通知门店商品已退回站点、等待处置选择（真正选择重新配送时再通知立即备货）
        notificationService.notifyStoreReturnPending(order,
                String.format("冷藏超时风险(%s)", reasonCn(riskReason)));
        log.info("已生成重新配送/退款选项: orderNo={}", order.getOrderNo());
    }

    private ColdChainResolution newResolution(DeliveryOrder order, ResolutionOption option) {
        ColdChainResolution r = new ColdChainResolution();
        r.setDelivery(order);
        r.setOptionType(option);
        r.setStatus(ResolutionStatus.PENDING);
        r.setRestockRequired(option == ResolutionOption.REDELIVER);
        return resolutionRepository.save(r);
    }

    /** 按配送单与选项类型选择处置（取该单对应类型的待处理选项） */
    @Transactional
    public Map<String, Object> decideByOption(Long orderId, ResolutionOption option, String decidedBy, String note) {
        DeliveryOrder order = mustGetColdChain(orderId);
        List<ColdChainResolution> pendings = resolutionRepository.findByDeliveryIdOrderByIdAsc(order.getId())
                .stream().filter(r -> r.getStatus() == ResolutionStatus.PENDING).toList();
        if (pendings.isEmpty()) {
            throw ApiException.conflict("没有待处理的冷藏处置选项（订单可能尚未退回站点）");
        }
        ColdChainResolution target = pendings.stream()
                .filter(r -> r.getOptionType() == option).findFirst()
                .orElseThrow(() -> ApiException.badRequest("该配送单没有待处理的 " + option + " 选项"));
        return resolutionView(decide(target.getId(), option, decidedBy, note));
    }

    /** 查询某单的处置选项（视图） */
    @Transactional
    public List<Map<String, Object>> resolutions(Long orderId) {
        mustGetColdChain(orderId);
        return resolutionRepository.findByDeliveryIdOrderByIdAsc(orderId).stream()
                .map(this::resolutionView).toList();
    }

    private Map<String, Object> resolutionView(ColdChainResolution r) {
        DeliveryOrder newOrder = r.getNewOrder();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("deliveryId", r.getDelivery().getId());
        m.put("option", r.getOptionType().name());
        m.put("status", r.getStatus().name());
        m.put("restockRequired", r.isRestockRequired());
        m.put("restockNotifiedAt", r.getRestockNotifiedAt() == null ? null
                : r.getRestockNotifiedAt().toString());
        m.put("decidedBy", r.getDecidedBy());
        m.put("decidedAt", r.getDecidedAt() == null ? null : r.getDecidedAt().toString());
        m.put("note", r.getNote());
        m.put("newOrderId", newOrder == null ? null : newOrder.getId());
        m.put("newOrderNo", newOrder == null ? null : newOrder.getOrderNo());
        return m;
    }

    /** 查询某单的温度/剩余时长采样曲线 */
    @Transactional
    public List<ColdChainSnapshot> snapshots(Long orderId) {
        mustGetColdChain(orderId);
        return snapshotRepository.findByDeliveryIdOrderByCreatedAtAsc(orderId);
    }

    /** 选择处置：REDELIVER 生成新配送单并通知门店重新备货；REFUND 标记退款并通知用户 */
    @Transactional
    public ColdChainResolution decide(Long resolutionId, ResolutionOption option, String decidedBy, String note) {
        ColdChainResolution resolution = resolutionRepository.findById(resolutionId)
                .orElseThrow(() -> ApiException.notFound("处置选项不存在: " + resolutionId));
        if (resolution.getStatus() != ResolutionStatus.PENDING) {
            throw ApiException.conflict("该选项已处理: " + resolution.getStatus());
        }
        DeliveryOrder order = resolution.getDelivery();
        if (resolution.getOptionType() != option) {
            throw ApiException.badRequest("选项类型不匹配: " + resolution.getOptionType());
        }

        // 同一配送单只允许选择一个选项，其余作废
        resolutionRepository.findByDeliveryIdOrderByIdAsc(order.getId()).forEach(other -> {
            if (!other.getId().equals(resolution.getId()) && other.getStatus() == ResolutionStatus.PENDING) {
                other.setStatus(ResolutionStatus.CANCELLED);
                resolutionRepository.save(other);
            }
        });

        resolution.setDecidedBy(decidedBy);
        resolution.setDecidedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            resolution.setNote(note);
        }

        if (option == ResolutionOption.REDELIVER) {
            DeliveryOrder newOrder = orderService.create(order.getOrderType(), order.getCommunity().getId(),
                    order.getBuilding().getId(), order.getUnitNo(), order.getRoomNo(), true,
                    LocalDateTime.now().plusMinutes(30), order.getUserName(), order.getUserPhone());
            resolution.setStatus(ResolutionStatus.REDELIVERING);
            resolution.setNewOrder(newOrder);
            resolution.setRestockNotifiedAt(LocalDateTime.now());
            resolutionRepository.save(resolution);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("newOrderNo", newOrder.getOrderNo());
            payload.put("newOrderId", newOrder.getId());
            payload.put("restockRequired", true);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_REDELIVERY_CREATED, EventActor.CS, "REDELIVER", null, payload);
            // 门店重新备货通知挂在原单时间线（新单另有 ORDER_CREATED 事件）
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_STOCK_REPREP, EventActor.SYSTEM, "RESTOCK", null, payload);
            notificationService.notifyStoreRestock(order, true, "重新配送");
        } else {
            resolution.setStatus(ResolutionStatus.REFUNDED);
            resolutionRepository.save(resolution);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("note", note);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_REFUNDED, EventActor.CS, "REFUND", null, payload);
            notificationService.notifyRefund(order, note);
            notificationService.notifyStoreRestock(order, false, "用户选择退款");
        }
        return resolution;
    }

    /** 单票冷藏单实时监控状态：温度、剩余时长、风险等级、等待归因、处置选项 */
    @Transactional
    public Map<String, Object> status(Long orderId) {
        DeliveryOrder order = mustGetColdChain(orderId);
        WaitAttribution attr = attributeWaits(order, LocalDateTime.now());
        long remaining = order.getColdChainDeadline() == null
                ? 0 : Duration.between(LocalDateTime.now(), order.getColdChainDeadline()).getSeconds();
        ColdChainSnapshot latest = snapshotRepository.findFirstByDeliveryIdOrderByCreatedAtDesc(order.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("orderType", order.getOrderType().name());
        result.put("community", order.getCommunity().getName());
        result.put("building", order.getBuilding().getName());
        result.put("roomNo", order.getRoomNo());
        result.put("status", order.getStatus().name());
        result.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        result.put("compartmentNo", order.getCompartmentNo());
        result.put("coldChainDeadline", order.getColdChainDeadline() == null
                ? null : order.getColdChainDeadline().toString());
        result.put("remainingSeconds", remaining);
        result.put("lastTemperatureC", order.getColdChainLastTemp());
        result.put("riskLevel", latest == null ? TemperatureRiskLevel.NORMAL.name() : latest.getRiskLevel().name());
        result.put("riskReason", latest == null ? "NONE" : latest.getRiskReason());
        result.put("warnedAt", order.getColdChainWarnedAt() == null ? null
                : order.getColdChainWarnedAt().toString());
        result.put("criticalAt", order.getColdChainCriticalAt() == null ? null
                : order.getColdChainCriticalAt().toString());
        result.put("timedOut", order.isColdChainTimedOut());
        result.put("returnedForRisk", order.isColdChainReturnedRisk());
        result.put("evidenceLocked", order.isColdChainEvidenceLocked());
        result.put("waitAttribution", waitsMap(attr));
        result.put("resolutions", resolutionRepository.findByDeliveryIdOrderByIdAsc(order.getId()).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("option", r.getOptionType().name());
                    m.put("status", r.getStatus().name());
                    m.put("restockRequired", r.isRestockRequired());
                    m.put("newOrderNo", r.getNewOrder() == null ? null : r.getNewOrder().getOrderNo());
                    return m;
                }).toList());
        return result;
    }

    /** 组装赔付判断证据：门禁/电梯/避障/路线等待、温度曲线、事件时间线、处置情况 */
    @Transactional
    public Map<String, Object> evidence(Long orderId) {
        DeliveryOrder order = mustGetColdChain(orderId);
        WaitAttribution attr = attributeWaits(order, LocalDateTime.now());
        double temp = order.getColdChainLastTemp() == null
                ? properties.getColdchainTempSafeC() : order.getColdChainLastTemp().doubleValue();
        long remaining = order.getColdChainDeadline() == null
                ? 0 : Duration.between(LocalDateTime.now(), order.getColdChainDeadline()).getSeconds();
        return buildEvidence(order, attr, temp, remaining, "LOCKED");
    }

    private Map<String, Object> buildEvidence(DeliveryOrder order, WaitAttribution attr,
                                              double temp, long remaining, String riskReason) {
        List<DeliveryEvent> events = eventRepository.findByDeliveryIdOrderByOccurredAtAsc(order.getId());
        List<ColdChainSnapshot> snapshots = snapshotRepository.findByDeliveryIdOrderByCreatedAtAsc(order.getId());
        List<ColdChainResolution> resolutions = resolutionRepository.findByDeliveryIdOrderByIdAsc(order.getId());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("orderNo", order.getOrderNo());
        evidence.put("orderType", order.getOrderType().name());
        evidence.put("coldChain", true);
        evidence.put("building", order.getBuilding().getName());
        evidence.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        evidence.put("coldChainDeadline", order.getColdChainDeadline() == null
                ? null : order.getColdChainDeadline().toString());
        evidence.put("finalTemperatureC", round(temp));
        evidence.put("remainingSecondsAtLock", remaining);
        evidence.put("riskReason", riskReason);
        evidence.put("waitAttribution", waitsMap(attr));
        evidence.put("snapshotCount", snapshots.size());
        evidence.put("temperatureCurve", snapshots.stream().map(s -> Map.of(
                "at", s.getCreatedAt().toString(),
                "temperatureC", s.getTemperatureC(),
                "riskLevel", s.getRiskLevel().name(),
                "remainingSeconds", s.getRemainingSeconds())).toList());
        evidence.put("eventTimeline", events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.getOccurredAt().toString());
            m.put("eventType", e.getEventType());
            m.put("actor", e.getActor());
            m.put("reason", e.getReason());
            m.put("waitSeconds", e.getWaitSeconds());
            return m;
        }).toList());
        evidence.put("resolutions", resolutions.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("option", r.getOptionType().name());
            m.put("status", r.getStatus().name());
            m.put("newOrderNo", r.getNewOrder() == null ? null : r.getNewOrder().getOrderNo());
            m.put("restockRequired", r.isRestockRequired());
            m.put("restockNotifiedAt", r.getRestockNotifiedAt() == null
                    ? null : r.getRestockNotifiedAt().toString());
            return m;
        }).toList());
        return evidence;
    }

    /**
     * 冷藏超时损失列表：损失（超时/风险退回）关联门禁等待、电梯等待与机器人路线等待，
     * 供客服端赔付判责与运营端复盘。
     */
    @Transactional
    public List<Map<String, Object>> losses(Long communityId) {
        List<DeliveryOrder> orders = orderRepository.findAll().stream()
                .filter(DeliveryOrder::isColdChain)
                .filter(o -> o.isColdChainTimedOut() || o.isColdChainReturnedRisk()
                        || o.getColdChainCriticalAt() != null)
                .filter(o -> communityId == null || communityId.equals(o.getCommunity().getId()))
                .toList();
        return orders.stream().map(order -> {
            WaitAttribution attr = attributeWaits(order, LocalDateTime.now());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", order.getId());
            row.put("orderNo", order.getOrderNo());
            row.put("orderType", order.getOrderType().name());
            row.put("community", order.getCommunity().getName());
            row.put("building", order.getBuilding().getName());
            row.put("status", order.getStatus().name());
            row.put("timedOut", order.isColdChainTimedOut());
            row.put("returnedForRisk", order.isColdChainReturnedRisk());
            row.put("evidenceLocked", order.isColdChainEvidenceLocked());
            row.put("lastTemperatureC", order.getColdChainLastTemp());
            row.put("waitAttribution", waitsMap(attr));
            row.put("resolutions", resolutionRepository.findByDeliveryIdOrderByIdAsc(order.getId()).stream()
                    .map(r -> r.getOptionType().name() + "=" + r.getStatus().name()).toList());
            return row;
        }).toList();
    }

    /** 当前仍处于预警中的冷藏单 */
    @Transactional
    public List<Map<String, Object>> alerts() {
        return orderRepository.findByColdChainTrueAndStatusIn(MONITORED).stream()
                .filter(o -> o.getColdChainWarnedAt() != null || o.getColdChainCriticalAt() != null)
                .map(o -> {
                    ColdChainSnapshot latest = snapshotRepository.findFirstByDeliveryIdOrderByCreatedAtDesc(o.getId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("orderId", o.getId());
                    row.put("orderNo", o.getOrderNo());
                    row.put("status", o.getStatus().name());
                    row.put("temperatureC", o.getColdChainLastTemp());
                    row.put("riskLevel", latest == null ? "WARNING" : latest.getRiskLevel().name());
                    row.put("remainingSeconds", latest == null ? null : latest.getRemainingSeconds());
                    row.put("warnedAt", o.getColdChainWarnedAt() == null ? null : o.getColdChainWarnedAt().toString());
                    row.put("criticalAt", o.getColdChainCriticalAt() == null ? null
                            : o.getColdChainCriticalAt().toString());
                    return row;
                }).toList();
    }

    public WaitAttribution attributeWaits(DeliveryOrder order, LocalDateTime now) {
        long access = 0;
        for (AccessRequest r : accessRepository.findByDeliveryId(order.getId())) {
            LocalDateTime end = r.getDecidedAt() != null ? r.getDecidedAt() : now;
            if (r.getRequestedAt() != null && end.isAfter(r.getRequestedAt())) {
                access += Duration.between(r.getRequestedAt(), end).getSeconds();
            }
        }
        long elevator = 0;
        for (ElevatorRequest r : elevatorRepository.findByDeliveryIdOrderByIdDesc(order.getId())) {
            LocalDateTime end = r.getResolvedAt() != null ? r.getResolvedAt() : now;
            if (r.getRequestedAt() != null && end.isAfter(r.getRequestedAt())) {
                elevator += Duration.between(r.getRequestedAt(), end).getSeconds();
            }
        }
        long obstacle = 0;
        long route = 0;
        for (DeliveryEvent e : eventRepository.findByDeliveryIdOrderByOccurredAtAsc(order.getId())) {
            if (EventType.OBSTACLE_WAIT.name().equals(e.getEventType()) && e.getWaitSeconds() != null) {
                obstacle += e.getWaitSeconds();
            }
            if (EventType.ROUTE_ADJUSTED.name().equals(e.getEventType()) && e.getWaitSeconds() != null) {
                route += e.getWaitSeconds();
            }
        }
        long total = access + elevator + obstacle + route;
        return new WaitAttribution(access, elevator, obstacle, route, total,
                WaitAttribution.dominant(access, elevator, obstacle, route));
    }

    private void saveSnapshot(DeliveryOrder order, double temperature, long remaining,
                              TemperatureRiskLevel level, WaitAttribution attr, String riskReason,
                              long snapshotCount) {
        ColdChainSnapshot s = new ColdChainSnapshot();
        s.setDelivery(order);
        s.setTemperatureC(BigDecimal.valueOf(temperature).setScale(2, RoundingMode.HALF_UP));
        s.setRemainingSeconds(remaining);
        s.setRiskLevel(level);
        s.setPhase(order.getStatus().name());
        s.setAccessWaitSeconds(attr.accessWaitSeconds());
        s.setElevatorWaitSeconds(attr.elevatorWaitSeconds());
        s.setObstacleWaitSeconds(attr.obstacleWaitSeconds());
        s.setRouteWaitSeconds(attr.routeWaitSeconds());
        s.setTotalWaitSeconds(attr.totalWaitSeconds());
        s.setRiskReason(riskReason);
        snapshotRepository.save(s);

        // 正常态采样落库即可，每 6 次巡检向统一事件流写一条温度上报（5s 巡检约 30s 一条），
        // 避免事件流被高频采样淹没；风险态另发 COLDCHAIN_WARNING 预警事件。
        if (level != TemperatureRiskLevel.NORMAL || snapshotCount % 6 == 0) {
            Map<String, Object> payload = riskPayload(order, temperature, remaining, attr, riskReason);
            payload.put("alertLevel", level.name());
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.COLDCHAIN_TEMP_REPORT, EventActor.SYSTEM, null, null, payload);
        }
    }

    private Map<String, Object> riskPayload(DeliveryOrder order, double temp, long remaining,
                                            WaitAttribution attr, String riskReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("compartmentNo", order.getCompartmentNo());
        payload.put("temperatureC", round(temp));
        payload.put("remainingSeconds", remaining);
        payload.put("coldChainDeadline", order.getColdChainDeadline() == null
                ? null : order.getColdChainDeadline().toString());
        payload.put("riskReason", riskReason);
        payload.put("waitAttribution", waitsMap(attr));
        return payload;
    }

    private Map<String, Object> waitsMap(WaitAttribution attr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessWaitSeconds", attr.accessWaitSeconds());
        m.put("elevatorWaitSeconds", attr.elevatorWaitSeconds());
        m.put("obstacleWaitSeconds", attr.obstacleWaitSeconds());
        m.put("routeWaitSeconds", attr.routeWaitSeconds());
        m.put("totalWaitSeconds", attr.totalWaitSeconds());
        m.put("dominantReason", attr.dominantReason());
        return m;
    }

    private String describeWaits(WaitAttribution attr) {
        return String.format("门禁等待%ds、电梯等待%ds、避障%ds、路线绕行%ds",
                attr.accessWaitSeconds(), attr.elevatorWaitSeconds(),
                attr.obstacleWaitSeconds(), attr.routeWaitSeconds());
    }

    private String reasonCn(String reason) {
        return switch (reason) {
            case "ACCESS_WAIT" -> "门禁等待";
            case "ELEVATOR_WAIT" -> "电梯等待";
            case "OBSTACLE_WAIT" -> "避障停留";
            case "ROUTE_DETOUR" -> "路线绕行";
            case "DEADLINE" -> "履约时限临近";
            case "TEMPERATURE" -> "载物仓温升";
            case "MANUAL" -> "人工判定退回";
            default -> "综合等待";
        };
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private DeliveryOrder mustGetColdChain(Long orderId) {
        DeliveryOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + orderId));
        if (!order.isColdChain()) {
            throw ApiException.badRequest("该订单不是冷藏订单: " + order.getOrderNo());
        }
        return order;
    }
}
