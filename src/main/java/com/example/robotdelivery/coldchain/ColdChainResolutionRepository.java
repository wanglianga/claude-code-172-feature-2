package com.example.robotdelivery.coldchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColdChainResolutionRepository extends JpaRepository<ColdChainResolution, Long> {

    List<ColdChainResolution> findByDeliveryIdOrderByIdAsc(Long deliveryId);

    Optional<ColdChainResolution> findFirstByDeliveryIdAndStatus(Long deliveryId, ResolutionStatus status);

    List<ColdChainResolution> findByStatus(ResolutionStatus status);
}
