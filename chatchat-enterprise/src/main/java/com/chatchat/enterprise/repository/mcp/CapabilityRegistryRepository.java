package com.chatchat.enterprise.repository.mcp;
import com.chatchat.enterprise.entity.mcp.CapabilityRegistryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CapabilityRegistryRepository extends JpaRepository<CapabilityRegistryEntry, String> { }
