package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /**
     * Locks the user's row until the current transaction ends, the lock the worker takes before
     * applying a reward. One user's completions then run one at a time, in either mode. FOR NO KEY
     * UPDATE does not block inserts of rows that only reference the user.
     *
     * @return the id, or empty if the user no longer exists
     */
    @Query(value = "select id from app_user where id = :id for no key update", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    /**
     * Signs the user out everywhere: every token issued before this carries an older version and
     * is refused from now on. One statement, so two of these at once both count.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "update app_user set token_version = token_version + 1 where id = :id", nativeQuery = true)
    int incrementTokenVersion(@Param("id") Long id);
}
