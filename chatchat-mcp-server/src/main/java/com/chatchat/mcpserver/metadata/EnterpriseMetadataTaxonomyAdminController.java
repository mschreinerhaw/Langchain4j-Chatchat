package com.chatchat.mcpserver.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/enterprise-metadata/taxonomy")
@RequiredArgsConstructor
public class EnterpriseMetadataTaxonomyAdminController {

    private final EnterpriseMetadataTaxonomyService taxonomyService;
    private final EnterpriseMetadataCatalog catalog;

    @GetMapping("/domains")
    public List<MetadataDomain> domains() {
        return taxonomyService.listDomains();
    }

    @PostMapping("/domains")
    public MetadataDomain createDomain(@RequestBody MetadataDomain request) {
        request.setId(null);
        return refresh(taxonomyService.saveDomain(request));
    }

    @PutMapping("/domains/{id}")
    public MetadataDomain updateDomain(@PathVariable String id, @RequestBody MetadataDomain request) {
        request.setId(id);
        return refresh(taxonomyService.saveDomain(request));
    }

    @DeleteMapping("/domains/{id}")
    public Map<String, Object> deleteDomain(@PathVariable String id) {
        taxonomyService.deleteDomain(id);
        catalog.refresh();
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/scenarios")
    public List<MetadataScenario> scenarios() {
        return taxonomyService.listScenarios();
    }

    @PostMapping("/scenarios")
    public MetadataScenario createScenario(@RequestBody MetadataScenario request) {
        request.setId(null);
        return refresh(taxonomyService.saveScenario(request));
    }

    @PutMapping("/scenarios/{id}")
    public MetadataScenario updateScenario(@PathVariable String id, @RequestBody MetadataScenario request) {
        request.setId(id);
        return refresh(taxonomyService.saveScenario(request));
    }

    @DeleteMapping("/scenarios/{id}")
    public Map<String, Object> deleteScenario(@PathVariable String id) {
        taxonomyService.deleteScenario(id);
        catalog.refresh();
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/terms")
    public List<MetadataTermMapping> terms(
        @RequestParam(name = "scenario", required = false) String scenario) {
        return taxonomyService.listTerms(scenario);
    }

    @PostMapping("/terms")
    public MetadataTermMapping createTerm(@RequestBody MetadataTermMapping request) {
        request.setId(null);
        return refresh(taxonomyService.saveTerm(request));
    }

    @PutMapping("/terms/{id}")
    public MetadataTermMapping updateTerm(@PathVariable String id,
                                          @RequestBody MetadataTermMapping request) {
        request.setId(id);
        return refresh(taxonomyService.saveTerm(request));
    }

    @DeleteMapping("/terms/{id}")
    public Map<String, Object> deleteTerm(@PathVariable String id) {
        taxonomyService.deleteTerm(id);
        catalog.refresh();
        return Map.of("deleted", true, "id", id);
    }

    private <T> T refresh(T saved) {
        catalog.refresh();
        return saved;
    }
}
