package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.order.DeliveryOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 冷链风险单退回站点后生成的处置选项：重新配送或退款。
 * 重新配送需要门店重新备货；退款通知门店无需备货。
 */
@Getter
@Setter
@Entity
@Table(name = "cold_chain_resolution")
public class ColdChainResolution {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false, length = 12)
    private ColdResolutionOption optionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ColdResolutionStatus status;

    /** 重新配送时生成的新配送单 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "new_delivery_id")
    private DeliveryOrder newDelivery;

    public Long getNewDeliveryId() {
        return newDelivery == null ? null : newDelivery.getId();
    }

    public String getNewOrderNo() {
        return newDelivery == null ? null : newDelivery.getOrderNo();
    }

    @Column(name = "restock_required", nullable = false)
    private boolean restockRequired;

    @Column(name = "store_name", length = 64)
    private String storeName;

    @Column(name = "store_notified_at")
    private LocalDateTime storeNotifiedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(length = 255)
    private String note;

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
