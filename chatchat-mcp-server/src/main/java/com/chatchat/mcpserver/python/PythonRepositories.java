package com.chatchat.mcpserver.python;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface PythonEnvironmentRepository extends JpaRepository<PythonEnvironment,String>{List<PythonEnvironment> findByStatusOrderByNameAsc(String status);List<PythonEnvironment> findAllByOrderByUpdatedAtDesc();}
interface PythonTemplateAssetRepository extends JpaRepository<PythonTemplate,String>{List<PythonTemplate> findByStatus(String status);List<PythonTemplate> findAllByOrderByUpdatedAtDesc();}
interface PythonRuntimeExecutionRepository extends JpaRepository<PythonExecution,String>{}
