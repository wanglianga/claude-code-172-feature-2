package com.example.robotdelivery.notification;

import com.example.robotdelivery.order.DeliveryOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 通知服务：模拟短信与小程序推送（真实环境对接短信网关/微信开放平台），
 * 以及客服工作台预警与门店备货通知。
 */
@Slf4j
@Service
public class NotificationService {

    private static final Map<com.example.robotdelivery.order.OrderType, String> TYPE_CN = Map.of(
            com.example.robotdelivery.order.OrderType.FOOD, "外卖",
            com.example.robotdelivery.order.OrderType.MEDICINE, "药品",
            com.example.robotdelivery.order.OrderType.GROCERY, "商超生鲜");

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 到达门口后通过短信 + 小程序通知用户取件。
     */
    public List<Notification> notifyArrival(DeliveryOrder order) {
        String content = String.format(
                "【社区配送】您的%s订单%s已送达%s%s栋%s%s，载物仓%s号仓，请尽快取件。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(),
                order.getCommunity().getName(),
                order.getBuilding().getName(),
                order.getUnitNo() == null ? "" : order.getUnitNo(),
                order.getRoomNo() == null ? "" : order.getRoomNo(),
                order.getCompartmentNo());
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content);
        log.info("已通知用户取件: deliveryId={} phone={} channels=[SMS, MINI_PROGRAM]",
                order.getId(), order.getUserPhone());
        return List.of(sms, mini);
    }

    /**
     * 冷链超时风险预警：提前通知客服与用户（短信 + 小程序 + 客服工作台）。
     *
     * @param critical true=严重风险（可能退回换品）；false=一般预警
     */
    public List<Notification> notifyColdChainRisk(DeliveryOrder order, boolean critical,
                                                  double tempCelsius, long remainingSeconds,
                                                  String riskFactor) {
        String level = critical ? "严重" : "预警";
        String userContent = String.format(
                "【社区配送·冷链%s】您的%s订单%s载物仓当前%.1f℃，预计剩余可配送%d秒，"
                        + "因%s可能无法全程冷链送达，客服正在协调，必要时将退回站点为您更换商品或退款。",
                level, TYPE_CN.getOrDefault(order.getOrderType(), ""), order.getOrderNo(),
                tempCelsius, remainingSeconds, riskFactorCn(riskFactor));
        Notification sms = save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), userContent);
        Notification mini = save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), userContent);

        String csContent = String.format(
                "【冷链%s】订单%s(%s) 载物仓%.1f℃，剩余可配送%d秒，主要风险因素：%s；"
                        + "收货%s%s栋%s，请提前介入（协调放行/电梯/退回换品）。",
                level, order.getOrderNo(), TYPE_CN.getOrDefault(order.getOrderType(), ""),
                tempCelsius, remainingSeconds, riskFactorCn(riskFactor),
                order.getCommunity().getName(), order.getBuilding().getName(),
                order.getRoomNo() == null ? "" : order.getRoomNo());
        Notification cs = save(order.getId(), NotifyChannel.CS_CONSOLE, "客服工作台", csContent);
        log.info("冷链风险通知: deliveryId={} level={} temp={}℃ remaining={}s channels=[SMS,MINI_PROGRAM,CS_CONSOLE]",
                order.getId(), level, tempCelsius, remainingSeconds);
        return List.of(sms, mini, cs);
    }

    /**
     * 通知门店是否需要重新备货（重新配送=备货；退款=无需备货）。
     */
    public Notification notifyStoreRestock(Long deliveryId, String storeName, String orderNo,
                                           boolean restockRequired) {
        String content = restockRequired
                ? String.format("【门店备货】冷链订单%s已退回站点并确认更换商品，请门店%s尽快重新备货，等待机器人重新配送。",
                        orderNo, storeName)
                : String.format("【门店通知】冷链订单%s已退回站点，用户选择退款，门店%s无需重新备货。",
                        orderNo, storeName);
        Notification n = save(deliveryId, NotifyChannel.STORE, storeName, content);
        log.info("门店通知: deliveryId={} store={} restockRequired={}", deliveryId, storeName, restockRequired);
        return n;
    }

    /** 退款结果通知用户 */
    public Notification notifyRefund(Long deliveryId, String target, String orderNo) {
        String content = String.format(
                "【社区配送·退款】冷链订单%s因无法全程冷链已退回站点，退款已发起，预计1-3个工作日原路退回，原配送证据保留用于赔付判断。",
                orderNo);
        return save(deliveryId, NotifyChannel.SMS, target, content);
    }

    public static String riskFactorCn(String riskFactor) {
        if (riskFactor == null) {
            return "载物仓温度变化";
        }
        return switch (riskFactor) {
            case "ACCESS_WAIT" -> "门禁等待";
            case "ELEVATOR_WAIT" -> "电梯等待";
            case "OBSTACLE_WAIT" -> "路线避障等待";
            case "COMPARTMENT_WARMING" -> "载物仓升温";
            default -> riskFactor;
        };
    }

    public Notification save(Long deliveryId, NotifyChannel channel, String target, String content) {
        Notification notification = new Notification();
        notification.setDeliveryId(deliveryId);
        notification.setChannel(channel);
        notification.setTarget(target);
        notification.setContent(content);
        notification.setStatus("SENT");
        return notificationRepository.save(notification);
    }
}
