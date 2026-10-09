package com.chatchat.enterprise.repository.mcp;
import com.chatchat.enterprise.entity.mcp.CapabilityManifestVersion;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CapabilityVersionRepository extends JpaRepository<CapabilityManifestVersion, String> { }
