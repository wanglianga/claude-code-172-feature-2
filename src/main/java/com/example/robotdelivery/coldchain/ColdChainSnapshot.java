package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.order.DeliveryOrder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 载物仓温度与剩余可配送时长快照。
 * 服务在配送途中持续计算：当前仓温、距冷藏履约截止的剩余时长，
 * 并把已发生的等待按门禁 / 电梯 / 避障 / 路线调整归因累计，作为超时风险与赔付判断依据。
 */
@Getter
@Setter
@Entity
@Table(name = "cold_chain_snapshot")
public class ColdChainSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    /** 载物仓当前温度（℃） */
    @Column(name = "temperature_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal temperatureC;

    /** 剩余可配送时长（秒，距冷藏履约截止，可能为负表示已超时） */
    @Column(name = "remaining_seconds", nullable = false)
    private long remainingSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private TemperatureRiskLevel riskLevel;

    /** 采样时机器人所处配送阶段（订单状态） */
    @Column(length = 20)
    private String phase;

    @Column(name = "access_wait_seconds", nullable = false)
    private long accessWaitSeconds;

    @Column(name = "elevator_wait_seconds", nullable = false)
    private long elevatorWaitSeconds;

    @Column(name = "obstacle_wait_seconds", nullable = false)
    private long obstacleWaitSeconds;

    @Column(name = "route_wait_seconds", nullable = false)
    private long routeWaitSeconds;

    @Column(name = "total_wait_seconds", nullable = false)
    private long totalWaitSeconds;

    /** 主导风险原因：ACCESS_WAIT / ELEVATOR_WAIT / OBSTACLE_WAIT / ROUTE_DETOUR / DEADLINE / TEMPERATURE */
    @Column(name = "risk_reason", length = 40)
    private String riskReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
