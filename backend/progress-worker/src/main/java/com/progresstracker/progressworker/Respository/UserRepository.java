package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {}
