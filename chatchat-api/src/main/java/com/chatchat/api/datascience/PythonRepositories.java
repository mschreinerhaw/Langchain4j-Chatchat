package com.chatchat.api.datascience;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

interface PythonAssetRepository extends JpaRepository<PythonAssetEntity,String> {
    List<PythonAssetEntity> findByTenantIdAndOwnerIdOrderByCreatedAtDesc(String tenantId,String ownerId);
    Optional<PythonAssetEntity> findByIdAndTenantIdAndOwnerId(String id,String tenantId,String ownerId);
    boolean existsByTenantIdAndOwnerIdAndStatus(String tenantId,String ownerId,String status);
}
interface PythonScriptRepository extends JpaRepository<PythonScriptEntity,String> {
    List<PythonScriptEntity> findByTenantIdAndOwnerIdOrderByUpdatedAtDesc(String tenantId,String ownerId);
    Optional<PythonScriptEntity> findByIdAndTenantIdAndOwnerId(String id,String tenantId,String ownerId);
    Optional<PythonScriptEntity> findByAssetIdAndFileName(String assetId,String fileName);
}
interface PythonScriptVersionRepository extends JpaRepository<PythonScriptVersionEntity,String> {
    List<PythonScriptVersionEntity> findByScriptIdOrderByVersionNumberDesc(String scriptId);
}
interface PythonTemplateRepository extends JpaRepository<PythonTemplateEntity,String> {
    List<PythonTemplateEntity> findByTenantIdOrderByPublishedAtDesc(String tenantId);
    List<PythonTemplateEntity> findByStatus(String status);
    Optional<PythonTemplateEntity> findByIdAndTenantId(String id,String tenantId);
    Optional<PythonTemplateEntity> findByToolName(String toolName);
}
interface PythonExecutionRepository extends JpaRepository<PythonExecutionEntity,String> {
    List<PythonExecutionEntity> findTop50ByTenantIdAndOwnerIdOrderByStartedAtDesc(String tenantId,String ownerId);
}
interface PythonDataFileRepository extends JpaRepository<PythonDataFileEntity,String> {
    List<PythonDataFileEntity> findByTenantIdAndOwnerIdOrderByCreatedAtDesc(String tenantId,String ownerId);
    Optional<PythonDataFileEntity> findByIdAndTenantIdAndOwnerId(String id,String tenantId,String ownerId);
    List<PythonDataFileEntity> findByStatusAndExpireAtBefore(String status,java.time.Instant expireAt);
}
