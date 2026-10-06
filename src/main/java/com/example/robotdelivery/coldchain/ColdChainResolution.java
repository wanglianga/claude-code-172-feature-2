package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.order.DeliveryOrder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 冷藏商品退回站点后生成的处置选项：重新配送（门店重新备货）或退款。
 */
@Getter
@Setter
@Entity
@Table(name = "cold_chain_resolution")
public class ColdChainResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private DeliveryOrder delivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false, length = 20)
    private ResolutionOption optionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResolutionStatus status;

    /** 选择重新配送后生成的新配送单 */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_order_id")
    private DeliveryOrder newOrder;

    /** 门店是否需要重新备货 */
    @Column(name = "restock_required", nullable = false)
    private boolean restockRequired;

    @Column(name = "restock_notified_at")
    private LocalDateTime restockNotifiedAt;

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
