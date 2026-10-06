package com.example.robotdelivery.coldchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ColdChainSnapshotRepository extends JpaRepository<ColdChainSnapshot, Long> {

    List<ColdChainSnapshot> findByDeliveryIdOrderByCreatedAtAsc(Long deliveryId);

    ColdChainSnapshot findFirstByDeliveryIdOrderByCreatedAtDesc(Long deliveryId);
}
