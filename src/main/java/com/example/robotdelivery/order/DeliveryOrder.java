package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.robot.Robot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "delivery_order")
public class DeliveryOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(name = "unit_no", length = 20)
    private String unitNo;

    @Column(name = "room_no", length = 20)
    private String roomNo;

    /** 是否需要冷藏 */
    @Column(name = "cold_chain", nullable = false)
    private boolean coldChain;

    /** 预计送达时间 */
    @Column(name = "estimated_delivery_at")
    private LocalDateTime estimatedDeliveryAt;

    @Column(name = "user_name", nullable = false, length = 64)
    private String userName;

    /** 用户联系方式（手机号） */
    @Column(name = "user_phone", nullable = false, length = 32)
    private String userPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeliveryStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "robot_id")
    private Robot robot;

    /** 载物仓编号 */
    @Column(name = "compartment_no", length = 10)
    private String compartmentNo;

    /** 人工接力配送员 */
    @Column(name = "relay_courier", length = 64)
    private String relayCourier;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_reason", length = 255)
    private String returnReason;

    /** 冷藏订单履约截止时间 */
    @Column(name = "cold_chain_deadline")
    private LocalDateTime coldChainDeadline;

    @Column(name = "cold_chain_timed_out", nullable = false)
    private boolean coldChainTimedOut;

    @Column(name = "pickup_timed_out", nullable = false)
    private boolean pickupTimedOut;

    /** 冷链风险预警级别：NONE/WARN/CRITICAL */
    @Enumerated(EnumType.STRING)
    @Column(name = "cold_chain_warn_level", length = 10)
    private ColdWarnLevel coldChainWarnLevel;

    /** 首次发出超时预警时间 */
    @Column(name = "cold_chain_warned_at")
    private LocalDateTime coldChainWarnedAt;

    /** 升级为严重风险时间（必要时退回站点更换商品） */
    @Column(name = "cold_chain_critical_at")
    private LocalDateTime coldChainCriticalAt;

    /** 最近一次持续计算的载物仓温度（℃） */
    @Column(name = "last_temp_celsius")
    private java.math.BigDecimal lastTempCelsius;

    /** 最近一次计算的剩余可配送时长（秒） */
    @Column(name = "remaining_seconds")
    private Long remainingSeconds;

    @Column(name = "last_calc_at")
    private LocalDateTime lastCalcAt;

    /** 因冷链风险退回站点更换商品 */
    @Column(name = "cold_return_for_swap", nullable = false)
    private boolean coldReturnForSwap;

    /** 若本单为重新配送单，指向原退回单 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_of_id")
    private DeliveryOrder replacementOf;

    public Long getReplacementOfId() {
        return replacementOf == null ? null : replacementOf.getId();
    }

    /** 备货门店（药品/生鲜） */
    @Column(name = "store_name", length = 64)
    private String storeName;

    /** 是否已通知门店重新备货 */
    @Column(name = "restock_notified", nullable = false)
    private boolean restockNotified;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
