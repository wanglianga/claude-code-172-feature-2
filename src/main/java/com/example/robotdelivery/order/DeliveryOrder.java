package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.robot.Robot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
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

    /** 首次冷藏超时风险预警（WARNING）时间 */
    @Column(name = "cold_chain_warned_at")
    private LocalDateTime coldChainWarnedAt;

    /** 严重风险（CRITICAL）时间，作为自动退回站点的判定点 */
    @Column(name = "cold_chain_critical_at")
    private LocalDateTime coldChainCriticalAt;

    /** 最新载物仓温度（℃，持续计算/设备上报） */
    @Column(name = "cold_chain_last_temp", precision = 5, scale = 2)
    private BigDecimal coldChainLastTemp;

    /** 是否因冷藏超时风险退回站点（区别于用户超时未取的退回） */
    @Column(name = "cold_chain_returned_risk", nullable = false)
    private boolean coldChainReturnedRisk;

    /** 原配送证据是否已封存（供赔付判断） */
    @Column(name = "cold_chain_evidence_locked", nullable = false)
    private boolean coldChainEvidenceLocked;

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
