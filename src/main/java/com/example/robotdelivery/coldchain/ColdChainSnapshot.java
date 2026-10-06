package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.ColdWarnLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链温度快照：服务持续计算载物仓温度与剩余可配送时长，
 * 并记录门禁等待 / 电梯等待 / 避障（路线）累计秒数，作为超时损失关联证据。
 */
@Getter
@Setter
@Entity
@Table(name = "cold_chain_snapshot")
public class ColdChainSnapshot {

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

    @Column(name = "robot_id")
    private Long robotId;

    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 载物仓温度（℃） */
    @Column(name = "temp_celsius", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempCelsius;

    /** 剩余可配送时长（秒） */
    @Column(name = "remaining_seconds", nullable = false)
    private long remainingSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "warn_level", nullable = false, length = 10)
    private ColdWarnLevel warnLevel;

    @Column(name = "access_wait_seconds", nullable = false)
    private long accessWaitSeconds;

    @Column(name = "elevator_wait_seconds", nullable = false)
    private long elevatorWaitSeconds;

    @Column(name = "obstacle_wait_seconds", nullable = false)
    private long obstacleWaitSeconds;

    @Column(name = "moving_seconds", nullable = false)
    private long movingSeconds;

    /** 主要风险因素：ACCESS_WAIT/ELEVATOR_WAIT/OBSTACLE_WAIT/COMPARTMENT_WARMING */
    @Column(name = "risk_factor", length = 30)
    private String riskFactor;

    @Column(name = "phase", length = 24)
    private String phase;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
