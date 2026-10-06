package com.example.robotdelivery.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "通知记录", description = "短信/小程序取件通知、客服冷链预警、门店备货通知")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Operation(summary = "按配送单查询通知记录（可按渠道 SMS/MINI_PROGRAM/CS_CONSOLE/STORE 过滤）")
    @GetMapping
    public List<Notification> byDelivery(@RequestParam Long deliveryId,
                                         @RequestParam(required = false) NotifyChannel channel) {
        List<Notification> all = notificationRepository.findByDeliveryId(deliveryId);
        return channel == null ? all
                : all.stream().filter(n -> n.getChannel() == channel).collect(Collectors.toList());
    }
}
