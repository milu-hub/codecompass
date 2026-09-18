package com.codecompass.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** share_snapshots 的仓储。 */
public interface ShareSnapshotRepository extends JpaRepository<ShareSnapshotEntity, String> {
}
