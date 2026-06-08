package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.ExternalOrg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalOrgRepository extends JpaRepository<ExternalOrg, Long> {
    List<ExternalOrg> findAllByOrderByGradeAscOrgOrderAscNameAsc();
}
