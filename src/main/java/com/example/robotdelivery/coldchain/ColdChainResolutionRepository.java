package com.example.robotdelivery.coldchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColdChainResolutionRepository extends JpaRepository<ColdChainResolution, Long> {

    List<ColdChainResolution> findByDelivery_IdOrderByIdDesc(Long deliveryId);

    Optional<ColdChainResolution> findFirstByDelivery_IdAndStatusOrderByIdDesc(
            Long deliveryId, ColdResolutionStatus status);

    Optional<ColdChainResolution> findByNewDelivery_Id(Long newDeliveryId);
}
