package com.chatchat.chat.skills;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillConfigRepository extends JpaRepository<SkillConfigEntity, String> {

    List<SkillConfigEntity> findByDefaultAgentTrue();
}
