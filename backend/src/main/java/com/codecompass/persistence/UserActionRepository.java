package com.codecompass.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** user_actions 的仓储。 */
public interface UserActionRepository extends JpaRepository<UserActionEntity, Long> {

    long countByClientIdAndAction(String clientId, String action);

    boolean existsByClientIdAndActionAndRefId(String clientId, String action, String refId);
}
