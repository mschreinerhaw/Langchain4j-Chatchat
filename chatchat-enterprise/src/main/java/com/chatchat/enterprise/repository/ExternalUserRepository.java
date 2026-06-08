package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.ExternalUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalUserRepository extends JpaRepository<ExternalUser, Long> {
    List<ExternalUser> findAllByOrderByUserIdAscNameAsc();
}
