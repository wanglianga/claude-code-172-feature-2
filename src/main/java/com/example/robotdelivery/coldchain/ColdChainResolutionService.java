package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.delivery.DeliveryService;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.ColdWarnLevel;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.OrderService;
import com.example.robotdelivery.order.OrderType;
import com.example.robotdelivery.cs.Responsibility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 冷链风险处置闭环：
 * 严重超时风险时退回站点更换商品（原配送证据保留给赔付判断）；
 * 退回后生成"重新配送 / 退款"选项——重新配送由门店重新备货并产生新配送单，
 * 退款则通知门店无需备货并通知用户；客服可登记冷链超时损失，
 * 损失自动关联门禁等待、电梯等待与机器人路线证据。
 */
@Slf4j
@Service
public class ColdChainResolutionService {

    private final DeliveryOrderRepository orderRepository;
    private final ColdChainResolutionRepository resolutionRepository;
    private final ColdChainLossRepository lossRepository;
    private final ColdChainSnapshotRepository snapshotRepository;
    private final DeliveryEventRepository eventRepository;
    private final DeliveryService deliveryService;
    private final OrderService orderService;
    private final ColdChainMonitorService monitorService;
    private final NotificationService notificationService;
    private final DeliveryEventService eventService;

    public ColdChainResolutionService(DeliveryOrderRepository orderRepository,
                                      ColdChainResolutionRepository resolutionRepository,
                                      ColdChainLossRepository lossRepository,
                                      ColdChainSnapshotRepository snapshotRepository,
                                      DeliveryEventRepository eventRepository,
                                      DeliveryService deliveryService,
                                      OrderService orderService,
                                      ColdChainMonitorService monitorService,
                                      NotificationService notificationService,
                                      DeliveryEventService eventService) {
        this.orderRepository = orderRepository;
        this.resolutionRepository = resolutionRepository;
        this.lossRepository = lossRepository;
        this.snapshotRepository = snapshotRepository;
        this.eventRepository = eventRepository;
        this.deliveryService = deliveryService;
        this.orderService = orderService;
        this.monitorService = monitorService;
        this.notificationService = notificationService;
        this.eventService = eventService;
    }

    /**
     * 冷链风险退回站点更换商品：进入退回流程并保留原配送证据（不删事件/快照/门禁与电梯请求）。
     */
    @Transactional
    public DeliveryOrder returnForSwap(Long orderId, String storeName) {
        DeliveryOrder order = mustGet(orderId);
        if (!order.isColdChain()) {
            throw ApiException.badRequest("非冷链订单不适用冷链退回换品");
        }
        ColdWarnLevel level = order.getColdChainWarnLevel() == null ? ColdWarnLevel.NONE
                : order.getColdChainWarnLevel();
        if (level == ColdWarnLevel.NONE && !order.isColdChainTimedOut()) {
            throw ApiException.conflict("当前无冷链超时风险（温度与剩余时长正常），无需退回站点更换商品");
        }

        String store = storeName != null && !storeName.isBlank() ? storeName : defaultStore(order.getOrderType());
        order.setStoreName(store);
        order.setColdReturnForSwap(true);
        orderRepository.save(order);

        // 复用统一退回状态机（RETURN_ALLOWED 校验 + RETURN_STARTED 事件 + 释放载物仓在确认退回时完成）
        deliveryService.startReturn(orderId, "冷链超时风险，退回站点更换商品");

        Map<String, Object> payload = basePayload(order);
        payload.put("storeName", store);
        payload.put("tempCelsius", order.getLastTempCelsius());
        payload.put("remainingSeconds", order.getRemainingSeconds());
        payload.put("evidence", buildEvidenceRefs(order));
        eventService.emit(orderId, order.getCommunity().getId(),
                EventType.RETURN_FOR_SWAP, EventActor.SYSTEM, payload);
        monitorService.clearTrack(orderId);
        log.info("冷链风险退回站点换品: orderNo={} store={}", order.getOrderNo(), store);
        return order;
    }

    /**
     * 确认退回站点、商品已更换：释放机器人，生成"重新配送 / 退款"两个待选处置项。
     */
    @Transactional
    public List<ColdChainResolution> confirmSwapReturn(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getStatus() != com.example.robotdelivery.order.DeliveryStatus.RETURNING) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许确认退回（需先退回站点）");
        }
        if (!order.isColdReturnForSwap()) {
            throw ApiException.conflict("该订单不是冷链换品退回单");
        }
        deliveryService.confirmReturn(orderId); // 释放机器人/载物仓 + RETURNED 事件

        Map<String, Object> swapPayload = basePayload(order);
        swapPayload.put("storeName", order.getStoreName());
        swapPayload.put("goodsStatus", ColdGoodsStatus.REPLACED.name());
        eventService.emit(orderId, order.getCommunity().getId(),
                EventType.GOODS_SWAPPED, EventActor.OPS, swapPayload);

        ColdChainResolution redeliver = newResolution(order, ColdResolutionOption.REDELIVER);
        ColdChainResolution refund = newResolution(order, ColdResolutionOption.REFUND);
        resolutionRepository.save(redeliver);
        resolutionRepository.save(refund);
        log.info("冷链退回已到站，生成重新配送/退款选项: orderNo={}", order.getOrderNo());
        return List.of(redeliver, refund);
    }

    /** 查询处置选项 */
    public List<ColdChainResolution> listResolutions(Long orderId) {
        return resolutionRepository.findByDelivery_IdOrderByIdDesc(orderId);
    }

    /**
     * 客服/运营确认处置方式：REDELIVER 重新备货并生成新配送单；REFUND 通知门店无需备货并通知用户退款。
     */
    @Transactional
    public ColdChainResolution decide(Long resolutionId, String decidedBy, String note) {
        ColdChainResolution resolution = resolutionRepository.findById(resolutionId)
                .orElseThrow(() -> ApiException.notFound("处置选项不存在: " + resolutionId));
        if (resolution.getStatus() != ColdResolutionStatus.PENDING) {
            throw ApiException.conflict("处置选项已处理: " + resolution.getStatus());
        }
        DeliveryOrder order = resolution.getDelivery();

        if (resolution.getOptionType() == ColdResolutionOption.REDELIVER) {
            return acceptRedeliver(resolution, order, decidedBy, note);
        }
        return acceptRefund(resolution, order, decidedBy, note);
    }

    private ColdChainResolution acceptRedeliver(ColdChainResolution resolution, DeliveryOrder origin,
                                                String decidedBy, String note) {
        // 生成新的冷链配送单（复制收货信息，重新计算履约时限），关联原单
        DeliveryOrder fresh = orderService.create(origin.getOrderType(), origin.getCommunity().getId(),
                origin.getBuilding().getId(), origin.getUnitNo(), origin.getRoomNo(), true,
                origin.getEstimatedDeliveryAt(), origin.getUserName(), origin.getUserPhone());
        fresh.setReplacementOf(origin);
        fresh.setStoreName(origin.getStoreName());
        orderRepository.save(fresh);

        resolution.setStatus(ColdResolutionStatus.ACCEPTED);
        resolution.setNewDelivery(fresh);
        resolution.setRestockRequired(true);
        resolution.setStoreName(origin.getStoreName());
        resolution.setStoreNotifiedAt(LocalDateTime.now());
        resolution.setDecidedBy(decidedBy);
        resolution.setDecidedAt(LocalDateTime.now());
        resolution.setNote(note);
        resolutionRepository.save(resolution);
        rejectOtherPending(origin.getId(), resolution.getId());

        notificationService.notifyStoreRestock(origin.getId(), origin.getStoreName(), origin.getOrderNo(), true);

        Map<String, Object> payload = basePayload(origin);
        payload.put("newDeliveryId", fresh.getId());
        payload.put("newOrderNo", fresh.getOrderNo());
        payload.put("storeName", origin.getStoreName());
        payload.put("decidedBy", decidedBy);
        eventService.emit(origin.getId(), origin.getCommunity().getId(),
                EventType.COLDCHAIN_REDELIVER_OPTION, EventActor.CS, payload);
        Map<String, Object> restock = basePayload(origin);
        restock.put("restockRequired", true);
        restock.put("newDeliveryId", fresh.getId());
        eventService.emit(origin.getId(), origin.getCommunity().getId(),
                EventType.RESTOCK_NOTIFY, EventActor.SYSTEM, "REDELIVER", null, restock);
        log.info("冷链单确认重新配送: origin={} new={} store={}",
                origin.getOrderNo(), fresh.getOrderNo(), origin.getStoreName());
        return resolution;
    }

    private ColdChainResolution acceptRefund(ColdChainResolution resolution, DeliveryOrder origin,
                                             String decidedBy, String note) {
        resolution.setStatus(ColdResolutionStatus.ACCEPTED);
        resolution.setRestockRequired(false);
        resolution.setStoreName(origin.getStoreName());
        resolution.setStoreNotifiedAt(LocalDateTime.now());
        resolution.setDecidedBy(decidedBy);
        resolution.setDecidedAt(LocalDateTime.now());
        resolution.setNote(note);
        resolutionRepository.save(resolution);
        rejectOtherPending(origin.getId(), resolution.getId());

        notificationService.notifyStoreRestock(origin.getId(), origin.getStoreName(), origin.getOrderNo(), false);
        notificationService.notifyRefund(origin.getId(), origin.getUserPhone(), origin.getOrderNo());

        Map<String, Object> payload = basePayload(origin);
        payload.put("storeName", origin.getStoreName());
        payload.put("decidedBy", decidedBy);
        payload.put("goodsStatus", ColdGoodsStatus.REFUNDED.name());
        eventService.emit(origin.getId(), origin.getCommunity().getId(),
                EventType.COLDCHAIN_REFUND_OPTION, EventActor.CS, payload);
        Map<String, Object> restock = basePayload(origin);
        restock.put("restockRequired", false);
        eventService.emit(origin.getId(), origin.getCommunity().getId(),
                EventType.RESTOCK_NOTIFY, EventActor.SYSTEM, "REFUND", null, restock);
        log.info("冷链单确认退款，门店无需备货: orderNo={}", origin.getOrderNo());
        return resolution;
    }

    private void rejectOtherPending(Long deliveryId, Long keepId) {
        resolutionRepository.findByDelivery_IdOrderByIdDesc(deliveryId).stream()
                .filter(r -> r.getStatus() == ColdResolutionStatus.PENDING && !r.getId().equals(keepId))
                .forEach(r -> {
                    r.setStatus(ColdResolutionStatus.REJECTED);
                    resolutionRepository.save(r);
                });
    }

    /**
     * 客服登记冷链超时损失：损失金额/商品状况，自动汇总门禁等待、电梯等待、避障与机器人路线证据，
     * 给出建议责任归属，原配送证据保留供赔付判断。
     */
    @Transactional
    public ColdChainLoss recordLoss(Long orderId, BigDecimal lossAmount, ColdGoodsStatus goodsStatus,
                                    String recordedBy, String note) {
        DeliveryOrder order = mustGet(orderId);
        ColdChainMonitorService.WaitStats stats = monitorService.computeStats(order, LocalDateTime.now());

        Responsibility suggested = suggestResponsibility(stats);
        String detail = String.format(
                "门禁等待%ds、电梯等待%ds、避障等待%ds、移动%ds、路线调整%d次；主要因素：%s。%s",
                stats.accessWaitSeconds(), stats.elevatorWaitSeconds(), stats.obstacleWaitSeconds(),
                stats.movingSeconds(), stats.routeAdjustments(),
                NotificationService.riskFactorCn(dominantFactor(stats)),
                note == null ? "" : note);

        ColdChainLoss loss = new ColdChainLoss();
        loss.setDelivery(order);
        loss.setLossAmount(lossAmount == null ? BigDecimal.ZERO : lossAmount);
        loss.setGoodsStatus(goodsStatus == null ? ColdGoodsStatus.AT_RISK : goodsStatus);
        loss.setAccessWaitSeconds(stats.accessWaitSeconds());
        loss.setElevatorWaitSeconds(stats.elevatorWaitSeconds());
        loss.setObstacleWaitSeconds(stats.obstacleWaitSeconds());
        loss.setMovingSeconds(stats.movingSeconds());
        loss.setRouteAdjustmentCount(stats.routeAdjustments());
        loss.setRouteEvidence(stats.routeEvidence());
        loss.setSuggestedResponsibility(suggested);
        loss.setResponsibilityDetail(detail);
        loss.setEvidenceRefs(buildEvidenceRefs(order));
        loss.setRecordedBy(recordedBy);
        lossRepository.save(loss);

        Map<String, Object> payload = basePayload(order);
        payload.put("lossId", loss.getId());
        payload.put("lossAmount", loss.getLossAmount());
        payload.put("goodsStatus", loss.getGoodsStatus().name());
        payload.put("accessWaitSeconds", stats.accessWaitSeconds());
        payload.put("elevatorWaitSeconds", stats.elevatorWaitSeconds());
        payload.put("obstacleWaitSeconds", stats.obstacleWaitSeconds());
        payload.put("routeAdjustmentCount", stats.routeAdjustments());
        payload.put("routeEvidence", stats.routeEvidence());
        payload.put("suggestedResponsibility", suggested.name());
        payload.put("evidenceRefs", loss.getEvidenceRefs());
        eventService.emit(orderId, order.getCommunity().getId(),
                EventType.COLDCHAIN_LOSS_ASSESSED, EventActor.CS, dominantFactor(stats),
                stats.totalWaitSeconds(), payload);
        log.info("冷链超时损失登记: orderNo={} amount={} responsibility={}",
                order.getOrderNo(), loss.getLossAmount(), suggested);
        return loss;
    }

    public List<ColdChainLoss> listLosses(Long orderId) {
        return lossRepository.findByDelivery_IdOrderByIdDesc(orderId);
    }

    /** 依据等待归因给出建议责任（最终责任由客服结案认定） */
    private Responsibility suggestResponsibility(ColdChainMonitorService.WaitStats stats) {
        String factor = dominantFactor(stats);
        return switch (factor) {
            case "ACCESS_WAIT" -> Responsibility.PROPERTY;
            case "ELEVATOR_WAIT" -> Responsibility.ELEVATOR_SYSTEM;
            case "OBSTACLE_WAIT" -> Responsibility.THIRD_PARTY;
            case "COMPARTMENT_WARMING" -> Responsibility.ROBOT_VENDOR;
            default -> Responsibility.NONE;
        };
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

    /** 汇总原配送证据引用（事件流 / 温度快照 / 门禁与电梯请求），用于赔付判断 */
    private String buildEvidenceRefs(DeliveryOrder order) {
        List<DeliveryEvent> keyEvents = eventRepository.findByDeliveryIdOrderByOccurredAtAsc(order.getId())
                .stream()
                .filter(e -> EventType.COLDCHAIN_WARNING.name().equals(e.getEventType())
                        || EventType.COLDCHAIN_CRITICAL.name().equals(e.getEventType())
                        || EventType.COLDCHAIN_TIMEOUT.name().equals(e.getEventType())
                        || EventType.ACCESS_REQUESTED.name().equals(e.getEventType())
                        || EventType.ACCESS_GRANTED.name().equals(e.getEventType())
                        || EventType.ACCESS_DENIED.name().equals(e.getEventType())
                        || EventType.ELEVATOR_REQUESTED.name().equals(e.getEventType())
                        || EventType.ELEVATOR_TIMEOUT.name().equals(e.getEventType())
                        || EventType.OBSTACLE_WAIT.name().equals(e.getEventType())
                        || EventType.ROUTE_ADJUSTED.name().equals(e.getEventType()))
                .toList();
        String events = keyEvents.stream()
                .map(e -> e.getEventType() + "#" + e.getId())
                .collect(Collectors.joining(","));
        long snapshotCount = snapshotRepository.countByDelivery_Id(order.getId());
        return "关键事件[" + events + "]; 温度快照" + snapshotCount + "条; "
                + "门禁/电梯等待与路线明细见统一事件流与监控视图; 订单号=" + order.getOrderNo();
    }

    private ColdChainResolution newResolution(DeliveryOrder order, ColdResolutionOption option) {
        ColdChainResolution r = new ColdChainResolution();
        r.setDelivery(order);
        r.setOptionType(option);
        r.setStatus(ColdResolutionStatus.PENDING);
        r.setRestockRequired(option == ColdResolutionOption.REDELIVER);
        r.setStoreName(order.getStoreName());
        return r;
    }

    private String defaultStore(OrderType type) {
        return switch (type) {
            case MEDICINE -> "社区药房";
            case GROCERY -> "生鲜门店";
            case FOOD -> "合作餐饮商户";
        };
    }

    private Map<String, Object> basePayload(DeliveryOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        return payload;
    }

    private DeliveryOrder mustGet(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + orderId));
    }
}
