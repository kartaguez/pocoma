package com.kartaguez.pocoma.infra.persistence.jpa.repository.projection;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceEntity;

public interface JpaPotBalanceRepository extends JpaRepository<JpaPotBalanceEntity, UUID> {

	List<JpaPotBalanceEntity> findByPotIdAndVersion(UUID potId, long version);
}
