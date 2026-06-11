package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.ExternalOrg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalOrgRepository extends JpaRepository<ExternalOrg, Long> {
    /**
     * Finds the all by order by grade asc org order asc name asc.
     *
     * @return the matching all by order by grade asc org order asc name asc
     */
    List<ExternalOrg> findAllByOrderByGradeAscOrgOrderAscNameAsc();
}
