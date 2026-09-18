package com.codecompass.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** learning_paths 的仓储。按 (repoUrl, commitSha) 唯一 —— 这就是 F2 的持久化缓存。 */
public interface LearningPathRepository extends JpaRepository<LearningPathEntity, Long> {

    Optional<LearningPathEntity> findByRepoUrlAndCommitSha(String repoUrl, String commitSha);

    /** PATH_FINISHED 判定用：该仓库最新一条学习路线。 */
    Optional<LearningPathEntity> findFirstByRepoUrlOrderByCreatedAtDesc(String repoUrl);
}
