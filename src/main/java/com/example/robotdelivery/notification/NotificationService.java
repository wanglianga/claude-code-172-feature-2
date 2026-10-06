package com.example.robotdelivery.notification;

import com.example.robotdelivery.order.DeliveryOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知服务：模拟短信与小程序推送（真实环境对接短信网关/微信开放平台），
 * 以及客服工作台预警、门店备货通知。
 */
@Slf4j
@Service
public class NotificationService {

    /** 客服工作台统一接收目标（模拟） */
    public static final String CS_TARGET = "CS_DESK";
    /** 门店备货通知统一接收目标（模拟） */
    public static final String STORE_TARGET = "STORE_DESK";

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
                "【社区配送】您的%s订单%s已送达%s%s栋%s单元%s，载物仓%s号仓，请尽快取件。",
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
     * 冷藏超时风险预警：同时通知客服与用户。
     *
     * @param critical 是否严重风险（CRITICAL）；false 为提前预警（WARNING）
     */
    public List<Notification> notifyColdChainRisk(DeliveryOrder order, boolean critical,
                                                  double temperatureC, long remainingSeconds,
                                                  String riskReason, String riskDetail) {
        String level = critical ? "严重" : "提前";
        String userContent = String.format(
                "【社区配送】您的%s冷藏订单%s出现%s超时风险：载物仓当前%.1f℃，预计还可保温%d秒（原因：%s）。"
                        + "我们正在协调处理，必要时将退回站点为您更换商品或安排退款，请保持电话畅通。",
                TYPE_CN.getOrDefault(order.getOrderType(), ""),
                order.getOrderNo(), level, temperatureC, remainingSeconds, riskDetail);
        String csContent = String.format(
                "【冷藏%S预警】订单%s(%s) 载物仓%.1f℃，剩余可配送%d秒，主导原因=%s。%s",
                level, order.getOrderNo(), order.getOrderType(), temperatureC, remainingSeconds,
                riskReason, critical ? "已达到退回阈值，请关注自动退回与赔付证据。" : "请提前关注门禁/电梯等待并准备干预。");

        List<Notification> sent = new ArrayList<>();
        sent.add(save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), userContent));
        sent.add(save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), userContent));
        sent.add(save(order.getId(), NotifyChannel.CS, CS_TARGET, csContent));
        log.info("冷藏超时风险已通知客服与用户: deliveryId={} critical={} temp={}℃ remain={}s",
                order.getId(), critical, temperatureC, remainingSeconds);
        return sent;
    }

    /**
     * 商品退回站点、处置选项已生成：预告门店等待用户/客服选择重新配送或退款，
     * 若选择重新配送需立即重新备货。
     */
    public Notification notifyStoreReturnPending(DeliveryOrder order, String reason) {
        String content = String.format(
                "【门店备货】冷藏订单%s(%s) 因%s已退回站点，原商品暂停使用。"
                        + "系统已生成重新配送/退款选项，若选择重新配送请立即重新备货，等待新配送单通知。",
                order.getOrderNo(), order.getOrderType(), reason);
        Notification n = save(order.getId(), NotifyChannel.STORE, STORE_TARGET, content);
        log.info("已预告门店商品退回、等待处置选择: deliveryId={}", order.getId());
        return n;
    }

    /**
     * 处置决定后通知门店：重新配送需立即备货；退款则核对退回商品并登记损耗。
     */
    public Notification notifyStoreRestock(DeliveryOrder order, boolean restockRequired, String reason) {
        String content = String.format(
                "【门店备货】冷藏订单%s(%s) 因%s已退回站点。%s请%s做好相应准备。",
                order.getOrderNo(), order.getOrderType(), reason,
                restockRequired ? "用户/客服选择重新配送，" : "无需重新备货（退款），",
                restockRequired ? "立即重新备货并等待新配送单" : "核对退回商品并登记损耗");
        Notification n = save(order.getId(), NotifyChannel.STORE, STORE_TARGET, content);
        log.info("已通知门店: deliveryId={} restockRequired={}", order.getId(), restockRequired);
        return n;
    }

    /** 退款结果通知用户（短信 + 小程序）。 */
    public List<Notification> notifyRefund(DeliveryOrder order, String note) {
        String content = String.format(
                "【社区配送】您的冷藏订单%s因冷藏超时风险未能完成，已为您办理退款%s，退回商品证据已封存备查。",
                order.getOrderNo(), note == null || note.isBlank() ? "" : "（" + note + "）");
        List<Notification> sent = new ArrayList<>();
        sent.add(save(order.getId(), NotifyChannel.SMS, order.getUserPhone(), content));
        sent.add(save(order.getId(), NotifyChannel.MINI_PROGRAM, order.getUserName(), content));
        return sent;
    }

    private Notification save(Long deliveryId, NotifyChannel channel, String target, String content) {
        Notification notification = new Notification();
        notification.setDeliveryId(deliveryId);
        notification.setChannel(channel);
        notification.setTarget(target);
        notification.setContent(content);
        notification.setStatus("SENT");
        return notificationRepository.save(notification);
    }
}
