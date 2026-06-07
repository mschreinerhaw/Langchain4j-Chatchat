package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.ExternalUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalUserRepository extends JpaRepository<ExternalUser, Long> {
    List<ExternalUser> findAllByOrderByUserIdAscNameAsc();
}
