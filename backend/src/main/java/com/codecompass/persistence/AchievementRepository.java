package com.codecompass.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** achievements 的仓储。 */
public interface AchievementRepository extends JpaRepository<AchievementEntity, Long> {

    boolean existsByClientIdAndCode(String clientId, String code);

    Optional<AchievementEntity> findByClientIdAndCode(String clientId, String code);
}
