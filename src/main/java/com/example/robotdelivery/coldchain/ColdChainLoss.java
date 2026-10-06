package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.order.DeliveryOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链超时损失：冷藏超时造成的损失关联门禁等待、电梯等待与机器人路线，
 * 保留原配送证据（事件/快照/门禁与电梯请求）供赔付判断与责任认定。
 */
@Getter
@Setter
@Entity
@Table(name = "cold_chain_loss")
public class ColdChainLoss {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    public Long getDeliveryId() {
        return delivery == null ? null : delivery.getId();
    }

    @Column(name = "loss_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal lossAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "goods_status", nullable = false, length = 20)
    private ColdGoodsStatus goodsStatus;

    @Column(name = "access_wait_seconds", nullable = false)
    private long accessWaitSeconds;

    @Column(name = "elevator_wait_seconds", nullable = false)
    private long elevatorWaitSeconds;

    @Column(name = "obstacle_wait_seconds", nullable = false)
    private long obstacleWaitSeconds;

    @Column(name = "moving_seconds", nullable = false)
    private long movingSeconds;

    /** 机器人路线调整次数 */
    @Column(name = "route_adjustment_count", nullable = false)
    private int routeAdjustmentCount;

    /** 路线绕行说明（机器人路线证据） */
    @Column(name = "route_evidence", columnDefinition = "text")
    private String routeEvidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_responsibility", nullable = false, length = 20)
    private com.example.robotdelivery.cs.Responsibility suggestedResponsibility;

    @Column(name = "responsibility_detail", length = 255)
    private String responsibilityDetail;

    /** 原配送证据引用（事件流/温度快照/门禁与电梯请求编号） */
    @Column(name = "evidence_refs", columnDefinition = "text")
    private String evidenceRefs;

    @Column(name = "recorded_by", length = 64)
    private String recordedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
