package com.codecompass.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** qa_history 的仓储。 */
public interface QaHistoryRepository extends JpaRepository<QaHistoryEntity, Long> {

    List<QaHistoryEntity> findTop10ByClientIdAndRepoUrlOrderByCreatedAtDesc(String clientId, String repoUrl);
}
