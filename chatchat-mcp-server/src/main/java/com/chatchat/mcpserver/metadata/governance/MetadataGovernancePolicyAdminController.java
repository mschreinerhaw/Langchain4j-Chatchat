package com.chatchat.mcpserver.metadata.governance;

import com.chatchat.mcpserver.metadata.tool.EnterpriseMetadataMcpToolPublisher;
import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.tool.MetadataGovernanceMcpToolPublisher;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/enterprise-metadata/governance-policy")
@RequiredArgsConstructor
public class MetadataGovernancePolicyAdminController {

    private final MetadataGovernancePolicyService policyService;
    private final EnterpriseMetadataMcpToolPublisher searchPublisher;
    private final MetadataGovernanceMcpToolPublisher governancePublisher;
    private final EnterpriseMetadataProperties properties;

    @GetMapping
    public Map<String, Object> current() {
        return Map.of(
            "status", policyService.status(),
            "policy", policyService.current()
        );
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody UpdateRequest request) {
        MetadataGovernancePolicy saved =
            policyService.save(request.policy(), request.expectedRevision());
        refreshPublishers();
        return Map.of(
            "status", policyService.status(),
            "policy", saved
        );
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        policyService.refresh();
        refreshPublishers();
        return Map.of(
            "status", policyService.status(),
            "policy", policyService.current()
        );
    }

    private void refreshPublishers() {
        if (properties.isEnabled()) {
            searchPublisher.refresh();
            governancePublisher.refresh();
        }
    }

    public record UpdateRequest(Long expectedRevision, MetadataGovernancePolicy policy) {
    }
}
