package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.runtime.market.analysis.FinancialAnalysisQuerySamples;
import com.chatchat.runtime.market.analysis.FinancialAnalysisQuerySamples.Sample;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seeds each built-in example at most once. A separate seed-state row is retained
 * after users delete an example, so deletion remains durable across restarts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialAnalysisQuerySampleSeeder {

    private static final String STATE_TABLE = "market_analysis_sample_seed_state";

    private final DatabaseQueryConfigRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    @Transactional
    public void seedOnce() {
        ensureStateTable();
        int created = 0;
        int upgraded = 0;
        for (Sample sample : FinancialAnalysisQuerySamples.all()) {
            if (seedRecorded(sample.id())) {
                Optional<DatabaseQueryConfig> existing = repository.findById(sample.id());
                if (existing.isPresent() && requiresStableObservationUpgrade(existing.get(), sample)) {
                    DatabaseQueryConfig config = existing.get();
                    config.setSqlTemplate(sample.sql());
                    config.setSqlStepsJson(writeJson(List.of(sqlStep(sample))));
                    repository.save(config);
                    upgraded++;
                }
                continue;
            }
            if (repository.findById(sample.id()).isEmpty()
                && repository.findByToolNameIgnoreCase(sample.toolName()).isEmpty()) {
                repository.save(toConfig(sample));
                created++;
            }
            recordSeed(sample.id());
        }
        log.info("Financial database analysis samples initialized created={} upgraded={} total={} enabled=false",
            created, upgraded, FinancialAnalysisQuerySamples.all().size());
    }

    private boolean requiresStableObservationUpgrade(DatabaseQueryConfig existing, Sample sample) {
        if (!FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID.equals(existing.getDatasourceId())
            || !"system".equalsIgnoreCase(existing.getOwner())) {
            return false;
        }
        String currentSql = existing.getSqlTemplate() == null ? "" : existing.getSqlTemplate();
        return sample.sql().contains("observation_rank")
            && !currentSql.contains("observation_rank");
    }

    private DatabaseQueryConfig toConfig(Sample sample) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId(sample.id());
        config.setToolName(sample.toolName());
        config.setTitle(sample.title());
        config.setDatasourceId(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
        config.setDescription(sample.description());
        config.setImplementationSteps(sample.implementationSteps());
        config.setBusinessGroup(FinancialAnalysisQuerySamples.BUSINESS_GROUP);
        config.setBusinessGroupName(FinancialAnalysisQuerySamples.BUSINESS_GROUP_NAME);
        config.setBusinessGroupDescription(FinancialAnalysisQuerySamples.BUSINESS_GROUP_DESCRIPTION);
        config.setSqlTemplate(sample.sql());
        config.setSqlStepsJson(writeJson(List.of(sqlStep(sample))));
        config.setInputSchemaJson(writeJson(sample.inputSchema()));
        config.setGovernanceJson(writeJson(Map.of(
            "intent", sample.intent(),
            "riskLevel", "read_only",
            "owner", "system",
            "evidencePolicy", "仅依据查询实际返回的数据分析；空结果、缺失字段或数据过期时必须明确披露",
            "limitations", "样例用于分析参考，不构成投资建议；启用前应先测试数据集和字段是否已完成采集",
            "dataScope", "系统内部治理的金融市场表，禁止访问MCP配置表"
        )));
        config.setRoutingLabelsJson(ModelProtocolJson.compact(sample.tags()));
        config.setRoutingLabels(sample.tags());
        config.setCapabilitiesJson(ModelProtocolJson.compact(List.of(
            "database_query", "financial_market_analysis", "read_only", "sql_query_execute")));
        config.setCapabilities(List.of(
            "database_query", "financial_market_analysis", "read_only", "sql_query_execute"));
        config.setTemplateIntent(sample.intent());
        config.setDatabaseType("financial_market");
        config.setTagsJson(ModelProtocolJson.compact(sample.tags()));
        config.setRiskLevel("read_only");
        config.setOwner("system");
        config.setRating(5.0);
        config.setUsageCount(0);
        config.setMaxRows(sample.maxRows());
        config.setTimeoutSeconds(30);
        config.setCacheEnabled(false);
        config.setCacheTtlSeconds(300);
        config.setCacheStorage("ROCKSDB");
        config.setReloadDrivers(false);
        config.setEnabled(false);
        return config;
    }

    private DatabaseQuerySqlStep sqlStep(Sample sample) {
        DatabaseQuerySqlStep step = new DatabaseQuerySqlStep();
        step.setSqlCode("ANALYZE");
        step.setSqlName(sample.title());
        step.setSqlDescription(sample.description());
        step.setSqlContent(sample.sql());
        step.setExecutionOrder(1);
        step.setDependencies(List.of());
        step.setWorkflowEnabled(false);
        step.setEnabled(true);
        step.setTimeoutSeconds(30);
        step.setFailureStrategy("STOP");
        step.setEmptyResultStrategy("CONTINUE");
        step.setMaxResultRows(sample.maxRows());
        step.setParameters(Map.of());
        step.setParameterMappings(List.of());
        DatabaseQueryResultSemantic semantic = new DatabaseQueryResultSemantic();
        semantic.setResultSetName(sample.intent());
        semantic.setBusinessEntity("financial_market_observation");
        semantic.setPrimaryKeys(List.of("observation_date", "record_key"));
        semantic.setTimeField("observation_date");
        semantic.setDataGranularity("按数据集定义的交易日、自然日或月度观测粒度");
        semantic.setEmptyMeaning("对应数据集尚未采集、采集日期不匹配，或输入代码不存在；空结果不代表业务值为零");
        semantic.setModelUsage(sample.resultSemantics());
        step.setResultSemantic(semantic);
        step.setReturnToModel(true);
        return step;
    }

    private boolean seedRecorded(String sampleId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from " + STATE_TABLE + " where sample_id=?", Integer.class, sampleId);
        return count != null && count > 0;
    }

    private void recordSeed(String sampleId) {
        jdbcTemplate.update("insert into " + STATE_TABLE + "(sample_id,seeded_at) values(?,?)",
            sampleId, Timestamp.from(Instant.now()));
    }

    private void ensureStateTable() {
        jdbcTemplate.execute("create table if not exists " + STATE_TABLE
            + " (sample_id varchar(64) not null primary key, seeded_at timestamp not null)");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize financial analysis sample", ex);
        }
    }
}
