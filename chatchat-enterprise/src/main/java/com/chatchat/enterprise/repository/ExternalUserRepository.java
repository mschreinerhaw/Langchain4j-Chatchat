package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.ExternalUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalUserRepository extends JpaRepository<ExternalUser, Long> {
    /**
     * Finds the all by order by user id asc name asc.
     *
     * @return the matching all by order by user id asc name asc
     */
    List<ExternalUser> findAllByOrderByUserIdAscNameAsc();
}
