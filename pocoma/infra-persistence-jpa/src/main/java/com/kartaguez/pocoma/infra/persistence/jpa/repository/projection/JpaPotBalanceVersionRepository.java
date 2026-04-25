package com.kartaguez.pocoma.infra.persistence.jpa.repository.projection;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceVersionEntity;

public interface JpaPotBalanceVersionRepository extends JpaRepository<JpaPotBalanceVersionEntity, UUID> {

	boolean existsByPotIdAndVersion(UUID potId, long version);
}
