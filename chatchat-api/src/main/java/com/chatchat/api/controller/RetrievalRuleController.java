package com.chatchat.api.controller;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.knowledgebase.search.rule.ChunkTypeRuleEntity;
import com.chatchat.knowledgebase.search.rule.QueryExpandRuleEntity;
import com.chatchat.knowledgebase.search.rule.QueryIntentRuleEntity;
import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import com.chatchat.knowledgebase.search.rule.RuleVersionEntity;
import com.chatchat.knowledgebase.search.rule.SemanticLexiconEntryEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/retrieval/rules")
@Tag(name = "Retrieval Rules", description = "Manage configurable retrieval rule data")
public class RetrievalRuleController {

    private final RetrievalRuleService ruleService;

    @GetMapping
    @Operation(summary = "List all retrieval rules")
    public ApiResponse<RetrievalRuleSummary> summary() {
        return ApiResponse.success(currentSummary());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh retrieval rule cache")
    public ApiResponse<RetrievalRuleSummary> refresh() {
        ruleService.refreshRules();
        return ApiResponse.success(currentSummary(), "Retrieval rule cache refreshed");
    }

    @GetMapping("/versions")
    @Operation(summary = "List retrieval rule versions")
    public ApiResponse<List<RuleVersionEntity>> versions() {
        return ApiResponse.success(ruleService.listVersions());
    }

    @PostMapping("/versions/publish")
    @Operation(summary = "Publish latest retrieval rule draft versions")
    public ApiResponse<RetrievalRuleSummary> publishAllLatest() {
        ruleService.publishAllLatest();
        return ApiResponse.success(currentSummary(), "Retrieval rule versions published");
    }

    @PostMapping("/versions/{type}/publish")
    @Operation(summary = "Publish latest retrieval rule draft version by type")
    public ApiResponse<RetrievalRuleSummary> publishLatest(@PathVariable("type") String type,
                                                           @RequestParam(value = "version", required = false) Integer version) {
        try {
            if (version == null) {
                ruleService.publishLatest(type);
            } else {
                ruleService.activateVersion(type, version);
            }
            return ApiResponse.success(currentSummary(), "Retrieval rule version published");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PostMapping("/versions/{type}/activate/{version}")
    @Operation(summary = "Activate retrieval rule version by type")
    public ApiResponse<RetrievalRuleSummary> activateVersion(@PathVariable("type") String type,
                                                             @PathVariable("version") Integer version) {
        try {
            ruleService.activateVersion(type, version);
            return ApiResponse.success(currentSummary(), "Retrieval rule version activated");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @GetMapping("/intent")
    @Operation(summary = "List query intent rules")
    public ApiResponse<List<QueryIntentRuleEntity>> intentRules() {
        return ApiResponse.success(ruleService.listIntentRules());
    }

    @PostMapping("/intent")
    @Operation(summary = "Create query intent rule")
    public ApiResponse<QueryIntentRuleEntity> createIntentRule(@RequestBody QueryIntentRuleEntity request) {
        try {
            request.setId(null);
            return ApiResponse.success(ruleService.saveIntentRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PutMapping("/intent/{id}")
    @Operation(summary = "Update query intent rule")
    public ApiResponse<QueryIntentRuleEntity> updateIntentRule(@PathVariable("id") Long id,
                                                               @RequestBody QueryIntentRuleEntity request) {
        try {
            request.setId(id);
            return ApiResponse.success(ruleService.saveIntentRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @DeleteMapping("/intent/{id}")
    @Operation(summary = "Delete query intent rule")
    public ApiResponse<Void> deleteIntentRule(@PathVariable("id") Long id) {
        ruleService.deleteIntentRule(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/chunk-type")
    @Operation(summary = "List chunk type rules")
    public ApiResponse<List<ChunkTypeRuleEntity>> chunkTypeRules() {
        return ApiResponse.success(ruleService.listChunkTypeRules());
    }

    @PostMapping("/chunk-type")
    @Operation(summary = "Create chunk type rule")
    public ApiResponse<ChunkTypeRuleEntity> createChunkTypeRule(@RequestBody ChunkTypeRuleEntity request) {
        try {
            request.setId(null);
            return ApiResponse.success(ruleService.saveChunkTypeRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PutMapping("/chunk-type/{id}")
    @Operation(summary = "Update chunk type rule")
    public ApiResponse<ChunkTypeRuleEntity> updateChunkTypeRule(@PathVariable("id") Long id,
                                                                @RequestBody ChunkTypeRuleEntity request) {
        try {
            request.setId(id);
            return ApiResponse.success(ruleService.saveChunkTypeRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @DeleteMapping("/chunk-type/{id}")
    @Operation(summary = "Delete chunk type rule")
    public ApiResponse<Void> deleteChunkTypeRule(@PathVariable("id") Long id) {
        ruleService.deleteChunkTypeRule(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/expand")
    @Operation(summary = "List query expansion rules")
    public ApiResponse<List<QueryExpandRuleEntity>> expandRules() {
        return ApiResponse.success(ruleService.listExpandRules());
    }

    @PostMapping("/expand")
    @Operation(summary = "Create query expansion rule")
    public ApiResponse<QueryExpandRuleEntity> createExpandRule(@RequestBody QueryExpandRuleEntity request) {
        try {
            request.setId(null);
            return ApiResponse.success(ruleService.saveExpandRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PutMapping("/expand/{id}")
    @Operation(summary = "Update query expansion rule")
    public ApiResponse<QueryExpandRuleEntity> updateExpandRule(@PathVariable("id") Long id,
                                                               @RequestBody QueryExpandRuleEntity request) {
        try {
            request.setId(id);
            return ApiResponse.success(ruleService.saveExpandRule(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @DeleteMapping("/expand/{id}")
    @Operation(summary = "Delete query expansion rule")
    public ApiResponse<Void> deleteExpandRule(@PathVariable("id") Long id) {
        ruleService.deleteExpandRule(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/lexicon")
    @Operation(summary = "List semantic lexicon entries")
    public ApiResponse<List<SemanticLexiconEntryEntity>> semanticLexiconEntries() {
        return ApiResponse.success(ruleService.listSemanticLexiconEntries());
    }

    @PostMapping("/lexicon")
    @Operation(summary = "Create semantic lexicon entry")
    public ApiResponse<SemanticLexiconEntryEntity> createSemanticLexiconEntry(@RequestBody SemanticLexiconEntryEntity request) {
        try {
            request.setId(null);
            request.setBuiltin(false);
            return ApiResponse.success(ruleService.saveSemanticLexiconEntry(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @PutMapping("/lexicon/{id}")
    @Operation(summary = "Update semantic lexicon entry")
    public ApiResponse<SemanticLexiconEntryEntity> updateSemanticLexiconEntry(@PathVariable("id") Long id,
                                                                              @RequestBody SemanticLexiconEntryEntity request) {
        try {
            request.setId(id);
            return ApiResponse.success(ruleService.saveSemanticLexiconEntry(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    @DeleteMapping("/lexicon/{id}")
    @Operation(summary = "Delete semantic lexicon entry")
    public ApiResponse<Void> deleteSemanticLexiconEntry(@PathVariable("id") Long id) {
        try {
            ruleService.deleteSemanticLexiconEntry(id);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        }
    }

    private RetrievalRuleSummary currentSummary() {
        RetrievalRuleService.RuleSnapshot snapshot = ruleService.snapshot();
        return new RetrievalRuleSummary(
            ruleService.listIntentRules(),
            ruleService.listChunkTypeRules(),
            ruleService.listExpandRules(),
            ruleService.listSemanticLexiconEntries(),
            ruleService.listVersions(),
            ruleService.activeVersions(),
            snapshot.refreshedAt()
        );
    }

    public record RetrievalRuleSummary(
        List<QueryIntentRuleEntity> intentRules,
        List<ChunkTypeRuleEntity> chunkTypeRules,
        List<QueryExpandRuleEntity> expandRules,
        List<SemanticLexiconEntryEntity> semanticLexiconEntries,
        List<RuleVersionEntity> versions,
        RetrievalRuleService.ActiveRuleVersions activeVersions,
        long refreshedAt
    ) {
    }
}
