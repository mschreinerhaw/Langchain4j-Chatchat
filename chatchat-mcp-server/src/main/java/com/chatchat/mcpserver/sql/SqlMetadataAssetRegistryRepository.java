package com.chatchat.mcpserver.sql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SqlMetadataAssetRegistryRepository extends JpaRepository<SqlMetadataAssetRegistry, String> {

    List<SqlMetadataAssetRegistry> findByDatasourceIdOrderByDatabaseNameAsc(String datasourceId);

    List<SqlMetadataAssetRegistry> findByDatasourceIdAndEnabledTrueOrderByDatabaseNameAsc(String datasourceId);

    Optional<SqlMetadataAssetRegistry> findByDatasourceIdAndDatabaseNameIgnoreCase(String datasourceId, String databaseName);

    void deleteByDatasourceId(String datasourceId);
}
