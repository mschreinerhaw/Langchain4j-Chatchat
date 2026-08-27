package com.chatchat.mcpserver.metadata.catalog;

import com.chatchat.mcpserver.metadata.ingestion.EnterpriseMetadataImportService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/enterprise-metadata")
@RequiredArgsConstructor
public class EnterpriseMetadataAdminController {

    private final EnterpriseMetadataCatalog catalog;
    private final EnterpriseMetadataImportService importService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return catalog.status();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return catalog.refresh();
    }

    @PostMapping("/import")
    public Map<String, Object> importWorkbooks() {
        EnterpriseMetadataImportService.ImportResult imported =
            importService.importConfiguredWorkbooks();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("imported", imported);
        result.put("catalog", catalog.refresh());
        return Map.copyOf(result);
    }
}
