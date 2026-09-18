package com.codecompass.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** quizzes 的仓储。 */
public interface QuizRepository extends JpaRepository<QuizEntity, String> {
}
