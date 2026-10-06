package com.example.robotdelivery.coldchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColdChainLossRepository extends JpaRepository<ColdChainLoss, Long> {

    List<ColdChainLoss> findByDelivery_IdOrderByIdDesc(Long deliveryId);

    Optional<ColdChainLoss> findFirstByDelivery_IdOrderByIdDesc(Long deliveryId);

    List<ColdChainLoss> findByDelivery_Community_Id(Long communityId);
}
