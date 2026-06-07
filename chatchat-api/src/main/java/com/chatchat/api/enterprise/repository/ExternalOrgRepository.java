package com.chatchat.api.enterprise.repository;

import com.chatchat.api.enterprise.entity.ExternalOrg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalOrgRepository extends JpaRepository<ExternalOrg, Long> {
    List<ExternalOrg> findAllByOrderByGradeAscOrgOrderAscNameAsc();
}
