package com.codecompass.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** notes 的仓储。 */
public interface NoteRepository extends JpaRepository<NoteEntity, Long> {

    List<NoteEntity> findByClientIdAndRepoUrl(String clientId, String repoUrl);

    Optional<NoteEntity> findByClientIdAndRepoUrlAndCodeUnitId(String clientId, String repoUrl, String codeUnitId);
}
