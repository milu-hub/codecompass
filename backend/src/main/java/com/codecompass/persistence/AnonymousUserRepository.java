package com.codecompass.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** anonymous_users 的仓储。 */
public interface AnonymousUserRepository extends JpaRepository<AnonymousUserEntity, String> {
}
