package com.codecompass.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** progress 的仓储。 */
public interface ProgressRepository extends JpaRepository<ProgressEntity, Long> {

    List<ProgressEntity> findByClientIdAndRepoUrl(String clientId, String repoUrl);

    Optional<ProgressEntity> findByClientIdAndRepoUrlAndCodeUnitId(String clientId, String repoUrl, String codeUnitId);

    long countByClientIdAndRepoUrlAndStatus(String clientId, String repoUrl, String status);
}
