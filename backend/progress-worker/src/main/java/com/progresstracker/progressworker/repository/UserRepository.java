package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Loads the user and holds a row lock on it until the transaction ends. Every reward
     * transaction for a user takes this lock first, so they run one at a time and none of
     * them can overwrite another's update to streaks, XP totals, or achievements.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
