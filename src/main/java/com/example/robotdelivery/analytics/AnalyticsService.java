package com.example.robotdelivery.analytics;

import com.example.robotdelivery.coldchain.ColdChainLoss;
import com.example.robotdelivery.coldchain.ColdChainLossRepository;
import com.example.robotdelivery.complaint.ComplaintRepository;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.order.OrderType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final DeliveryEventRepository eventRepository;
    private final DeliveryOrderRepository orderRepository;
    private final ComplaintRepository complaintRepository;
    private final ColdChainLossRepository coldChainLossRepository;

    public AnalyticsService(DeliveryEventRepository eventRepository,
                            DeliveryOrderRepository orderRepository,
                            ComplaintRepository complaintRepository,
                            ColdChainLossRepository coldChainLossRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.complaintRepository = complaintRepository;
        this.coldChainLossRepository = coldChainLossRepository;
    }

    /**
     * 物业端：机器人是否影响居民通行。
     * 汇总避障停留、施工绕行、电梯满载/超时、夜间限制、投诉等指标并给出影响等级。
     */
    public Map<String, Object> passageImpact(Long communityId) {
        List<DeliveryEvent> obstacleEvents =
                eventRepository.findByCommunityIdAndEventType(communityId, EventType.OBSTACLE_WAIT.name());
        long obstacleCount = obstacleEvents.size();
        long totalWaitSeconds = obstacleEvents.stream()
                .mapToLong(e -> e.getWaitSeconds() == null ? 0L : e.getWaitSeconds()).sum();
        long elderlyChild = obstacleEvents.stream()
                .filter(e -> "ELDERLY_CHILD".equals(e.getReason())).count();
        long constructionDetours = eventRepository.countByCommunityIdAndEventTypeAndReason(
                communityId, EventType.ROUTE_ADJUSTED.name(), "CONSTRUCTION");
        long routeAdjustments = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.ROUTE_ADJUSTED.name());
        long elevatorFull = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.ELEVATOR_FULL.name());
        long elevatorTimeout = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.ELEVATOR_TIMEOUT.name());
        long nightRestricted = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.NIGHT_RESTRICTED.name());
        long accessDenied = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.ACCESS_DENIED.name());
        long lowBattery = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.LOW_BATTERY.name());
        long complaints = complaintRepository.countByDelivery_Community_Id(communityId);
        long deliveries = orderRepository.countByCommunityId(communityId);

        long coldChainWarnings = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.COLDCHAIN_WARNING.name());
        long coldChainCritical = eventRepository.countByCommunityIdAndEventType(
                communityId, EventType.COLDCHAIN_CRITICAL.name());
        // 冷链超时损失关联门禁等待/电梯等待/机器人路线
        List<ColdChainLoss> losses = coldChainLossRepository.findByDelivery_Community_Id(communityId);
        long coldChainLossCount = losses.size();
        java.math.BigDecimal coldChainLossAmount = losses.stream()
                .map(ColdChainLoss::getLossAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        long lossByAccess = losses.stream()
                .filter(l -> l.getSuggestedResponsibility() == Responsibility.PROPERTY).count();
        long lossByElevator = losses.stream()
                .filter(l -> l.getSuggestedResponsibility() == Responsibility.ELEVATOR_SYSTEM).count();

        long impactScore = obstacleCount + constructionDetours * 2 + elevatorFull
                + nightRestricted * 2 + complaints * 3;
        String impactLevel = impactScore == 0 ? "无影响" : impactScore < 5 ? "低" : impactScore < 15 ? "中" : "高";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("communityId", communityId);
        result.put("totalDeliveries", deliveries);
        result.put("obstacleStopCount", obstacleCount);
        result.put("elderlyChildStops", elderlyChild);
        result.put("totalObstacleWaitSeconds", totalWaitSeconds);
        result.put("avgObstacleWaitSeconds", obstacleCount == 0 ? 0 : totalWaitSeconds / obstacleCount);
        result.put("constructionDetours", constructionDetours);
        result.put("routeAdjustments", routeAdjustments);
        result.put("elevatorFullCount", elevatorFull);
        result.put("elevatorTimeoutCount", elevatorTimeout);
        result.put("nightRestrictedCount", nightRestricted);
        result.put("accessDeniedCount", accessDenied);
        result.put("lowBatteryCount", lowBattery);
        result.put("complaintCount", complaints);
        result.put("coldChainWarningCount", coldChainWarnings);
        result.put("coldChainCriticalCount", coldChainCritical);
        result.put("coldChainLossCount", coldChainLossCount);
        result.put("coldChainLossAmount", coldChainLossAmount);
        result.put("coldChainLossByAccessWait", lossByAccess);
        result.put("coldChainLossByElevatorWait", lossByElevator);
        result.put("impactScore", impactScore);
        result.put("impactLevel", impactLevel);
        result.put("affectsResidents", impactScore >= 5);
        result.put("suggestion", switch (impactLevel) {
            case "高" -> "机器人对居民通行影响较高，建议限制高峰时段运行或缩减投放";
            case "中" -> "存在一定通行影响，建议优化路线与避障策略";
            case "低" -> "影响较小，可继续运行并持续观察";
            default -> "暂无通行影响记录";
        });
        return result;
    }

    /**
     * 运营端：按小区、楼栋、订单类型评估机器人投放效率。
     */
    public List<Map<String, Object>> opsEfficiency(Long communityId, Long buildingId, OrderType orderType) {
        Specification<DeliveryOrder> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (communityId != null) {
                predicates.add(cb.equal(root.get("community").get("id"), communityId));
            }
            if (buildingId != null) {
                predicates.add(cb.equal(root.get("building").get("id"), buildingId));
            }
            if (orderType != null) {
                predicates.add(cb.equal(root.get("orderType"), orderType));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<DeliveryOrder> orders = orderRepository.findAll(spec);

        record Key(Long communityId, String community, Long buildingId, String building, OrderType type) {
        }
        Map<Key, List<DeliveryOrder>> grouped = orders.stream().collect(Collectors.groupingBy(
                o -> new Key(o.getCommunity().getId(), o.getCommunity().getName(),
                        o.getBuilding().getId(), o.getBuilding().getName(), o.getOrderType()),
                LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        grouped.forEach((key, list) -> {
            long total = list.size();
            long signed = list.stream().filter(o -> o.getStatus() == DeliveryStatus.SIGNED).count();
            long returned = list.stream().filter(o -> o.getStatus() == DeliveryStatus.RETURNED).count();
            long cancelled = list.stream().filter(o -> o.getStatus() == DeliveryStatus.CANCELLED).count();
            long exception = list.stream().filter(o -> o.getStatus() == DeliveryStatus.EXCEPTION).count();
            long inProgress = total - signed - returned - cancelled;
            long coldChainTimeouts = list.stream().filter(DeliveryOrder::isColdChainTimedOut).count();
            long pickupTimeouts = list.stream().filter(DeliveryOrder::isPickupTimedOut).count();
            long coldChainWarned = list.stream()
                    .filter(o -> o.getColdChainWarnLevel() == com.example.robotdelivery.order.ColdWarnLevel.WARN
                            || o.getColdChainWarnLevel() == com.example.robotdelivery.order.ColdWarnLevel.CRITICAL)
                    .count();
            long coldChainCritical = list.stream()
                    .filter(o -> o.getColdChainWarnLevel() == com.example.robotdelivery.order.ColdWarnLevel.CRITICAL)
                    .count();
            long coldReturnForSwap = list.stream().filter(DeliveryOrder::isColdReturnForSwap).count();
            double avgSeconds = list.stream()
                    .filter(o -> o.getSignedAt() != null)
                    .mapToLong(o -> Duration.between(o.getCreatedAt(), o.getSignedAt()).getSeconds())
                    .average().orElse(0);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("communityId", key.communityId());
            row.put("community", key.community());
            row.put("buildingId", key.buildingId());
            row.put("building", key.building());
            row.put("orderType", key.type());
            row.put("total", total);
            row.put("signed", signed);
            row.put("returned", returned);
            row.put("cancelled", cancelled);
            row.put("exception", exception);
            row.put("inProgress", inProgress);
            row.put("successRate", total == 0 ? 0 : Math.round(signed * 1000.0 / total) / 10.0);
            row.put("avgDeliverySeconds", Math.round(avgSeconds));
            row.put("coldChainTimeouts", coldChainTimeouts);
            row.put("coldChainWarned", coldChainWarned);
            row.put("coldChainCritical", coldChainCritical);
            row.put("coldReturnForSwap", coldReturnForSwap);
            row.put("pickupTimeouts", pickupTimeouts);
            rows.add(row);
        });
        return rows;
    }
}
