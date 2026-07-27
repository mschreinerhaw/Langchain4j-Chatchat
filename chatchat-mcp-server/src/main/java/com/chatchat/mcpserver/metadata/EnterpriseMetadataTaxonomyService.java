package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class EnterpriseMetadataTaxonomyService {

    private static final String DEFAULT_SCENARIO = "general_metadata";

    private final MetadataDomainRepository domainRepository;
    private final MetadataScenarioRepository scenarioRepository;
    private final MetadataTermMappingRepository termRepository;
    private final EnterpriseMetadataProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CachedTaxonomy> cache = new AtomicReference<>();

    public EnterpriseMetadataTaxonomyService(MetadataDomainRepository domainRepository,
                                             MetadataScenarioRepository scenarioRepository,
                                             MetadataTermMappingRepository termRepository,
                                             EnterpriseMetadataProperties properties,
                                             ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.scenarioRepository = scenarioRepository;
        this.termRepository = termRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 90)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        seedDefaults();
        invalidate();
    }

    @Transactional(readOnly = true)
    public TaxonomySnapshot taxonomy() {
        String provider = properties.getScenarioClassification().getProvider();
        if (provider != null && !"database".equalsIgnoreCase(provider.trim())) {
            throw new IllegalStateException("Unsupported enterprise metadata taxonomy provider: " + provider);
        }
        CachedTaxonomy current = cache.get();
        if (current != null && current.expiresAt().isAfter(Instant.now())) {
            return current.snapshot();
        }
        synchronized (cache) {
            current = cache.get();
            if (current != null && current.expiresAt().isAfter(Instant.now())) {
                return current.snapshot();
            }
            TaxonomySnapshot loaded = loadTaxonomy();
            int ttl = Math.max(1, properties.getScenarioClassification().getCacheTtlSeconds());
            cache.set(new CachedTaxonomy(loaded, Instant.now().plus(Duration.ofSeconds(ttl))));
            return loaded;
        }
    }

    @Transactional(readOnly = true)
    public List<MetadataDomain> listDomains() {
        return domainRepository.findAllByOrderByPriorityAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<MetadataScenario> listScenarios() {
        return scenarioRepository.findAllByOrderByPriorityAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<MetadataTermMapping> listTerms(String scenarioIdOrCode) {
        if (scenarioIdOrCode == null || scenarioIdOrCode.isBlank()) {
            return termRepository.findAllByOrderByPriorityAscTermAsc();
        }
        return termRepository.findByScenarioIdOrderByPriorityAscTermAsc(requireScenario(scenarioIdOrCode).getId());
    }

    @Transactional
    public MetadataDomain saveDomain(MetadataDomain draft) {
        if (draft == null) throw new IllegalArgumentException("domain is required");
        String code = normalizeCode(draft.getCode());
        MetadataDomain target = entity(draft.getId(), domainRepository, MetadataDomain::new, "Metadata domain");
        domainRepository.findByCodeIgnoreCase(code)
            .filter(existing -> !existing.getId().equals(target.getId()))
            .ifPresent(existing -> { throw new IllegalArgumentException("Domain code already exists: " + code); });
        target.setCode(code);
        target.setName(required(draft.getName(), "name"));
        target.setDescription(text(draft.getDescription()));
        target.setPriority(draft.getPriority());
        target.setEnabled(draft.isEnabled());
        MetadataDomain saved = domainRepository.save(target);
        invalidate();
        return saved;
    }

    @Transactional
    public MetadataScenario saveScenario(MetadataScenario draft) {
        if (draft == null) throw new IllegalArgumentException("scenario is required");
        String code = normalizeCode(draft.getCode());
        MetadataScenario target = entity(draft.getId(), scenarioRepository, MetadataScenario::new, "Metadata scenario");
        scenarioRepository.findByCodeIgnoreCase(code)
            .filter(existing -> !existing.getId().equals(target.getId()))
            .ifPresent(existing -> { throw new IllegalArgumentException("Scenario code already exists: " + code); });
        if (draft.getDomainId() != null && !draft.getDomainId().isBlank()
            && !domainRepository.existsById(draft.getDomainId())) {
            throw new IllegalArgumentException("Metadata domain not found: " + draft.getDomainId());
        }
        target.setCode(code);
        target.setName(required(draft.getName(), "name"));
        target.setDescription(text(draft.getDescription()));
        target.setDomainId(blankToNull(draft.getDomainId()));
        target.setMetadataTypesJson(writeStringList(readStringList(draft.getMetadataTypesJson())));
        target.setPriority(draft.getPriority());
        if (target.isFallbackScenario() && (!draft.isFallbackScenario() || !draft.isEnabled())) {
            boolean replacementExists = scenarioRepository.findFirstByFallbackScenarioTrue()
                .filter(existing -> !existing.getId().equals(target.getId()) && existing.isEnabled())
                .isPresent();
            if (!replacementExists) {
                throw new IllegalArgumentException("An enabled fallback metadata scenario is required");
            }
        }
        target.setFallbackScenario(draft.isFallbackScenario());
        target.setEnabled(draft.isEnabled());
        if (target.isFallbackScenario()) {
            scenarioRepository.findAll().stream()
                .filter(existing -> existing.isFallbackScenario() && !existing.getId().equals(target.getId()))
                .forEach(existing -> {
                    existing.setFallbackScenario(false);
                    scenarioRepository.save(existing);
                });
        }
        MetadataScenario saved = scenarioRepository.save(target);
        invalidate();
        return saved;
    }

    @Transactional
    public MetadataTermMapping saveTerm(MetadataTermMapping draft) {
        if (draft == null) throw new IllegalArgumentException("term mapping is required");
        MetadataScenario scenario = requireScenario(draft.getScenarioId());
        String term = required(draft.getTerm(), "term");
        String normalized = term.toLowerCase(Locale.ROOT);
        MetadataTermMapping target = entity(draft.getId(), termRepository, MetadataTermMapping::new,
            "Metadata term mapping");
        termRepository.findByScenarioIdAndNormalizedTerm(scenario.getId(), normalized)
            .filter(existing -> !existing.getId().equals(target.getId()))
            .ifPresent(existing -> { throw new IllegalArgumentException("Scenario term already exists: " + term); });
        target.setScenarioId(scenario.getId());
        target.setTerm(term);
        target.setNormalizedTerm(normalized);
        target.setWeight(draft.getWeight() == null || draft.getWeight().signum() <= 0
            ? BigDecimal.ONE : draft.getWeight());
        target.setMatchType(normalizeMatchType(draft.getMatchType()));
        target.setPriority(draft.getPriority());
        target.setEnabled(draft.isEnabled());
        MetadataTermMapping saved = termRepository.save(target);
        invalidate();
        return saved;
    }

    @Transactional
    public void deleteDomain(String id) {
        if (scenarioRepository.countByDomainId(id) > 0) {
            throw new IllegalArgumentException("Domain is referenced by metadata scenarios");
        }
        domainRepository.deleteById(id);
        invalidate();
    }

    @Transactional
    public void deleteScenario(String id) {
        MetadataScenario scenario = scenarioRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Metadata scenario not found: " + id));
        if (scenario.isFallbackScenario()) {
            throw new IllegalArgumentException("Fallback metadata scenario cannot be deleted");
        }
        termRepository.deleteByScenarioId(id);
        scenarioRepository.deleteById(id);
        invalidate();
    }

    @Transactional
    public void deleteTerm(String id) {
        termRepository.deleteById(id);
        invalidate();
    }

    public void invalidate() {
        cache.set(null);
    }

    private TaxonomySnapshot loadTaxonomy() {
        List<MetadataScenario> scenarios = scenarioRepository.findByEnabledTrueOrderByPriorityAscNameAsc();
        List<String> scenarioIds = scenarios.stream().map(MetadataScenario::getId).toList();
        Map<String, List<TermDefinition>> terms = new LinkedHashMap<>();
        if (!scenarioIds.isEmpty()) {
            for (MetadataTermMapping mapping :
                termRepository.findByScenarioIdInAndEnabledTrueOrderByPriorityAscTermAsc(scenarioIds)) {
                terms.computeIfAbsent(mapping.getScenarioId(), ignored -> new ArrayList<>())
                    .add(new TermDefinition(mapping.getTerm(), mapping.getNormalizedTerm(),
                        mapping.getWeight().doubleValue(), mapping.getMatchType(), mapping.getPriority()));
            }
        }
        List<ScenarioDefinition> definitions = scenarios.stream()
            .map(scenario -> new ScenarioDefinition(
                scenario.getId(), scenario.getCode(), scenario.getName(), text(scenario.getDescription()),
                scenario.getDomainId(), readStringList(scenario.getMetadataTypesJson()), scenario.getPriority(),
                scenario.isFallbackScenario(), List.copyOf(terms.getOrDefault(scenario.getId(), List.of()))
            ))
            .toList();
        ScenarioDefinition fallback = definitions.stream()
            .filter(ScenarioDefinition::fallback)
            .findFirst()
            .orElseGet(() -> definitions.stream()
                .filter(item -> DEFAULT_SCENARIO.equals(item.code()))
                .findFirst()
                .orElse(new ScenarioDefinition("fallback", DEFAULT_SCENARIO, "通用数据标准",
                    "通用企业数据标准、字段定义、业务词根和代码字典场景。", null,
                    List.of(), Integer.MAX_VALUE, true, List.of())));
        return new TaxonomySnapshot(definitions.stream().filter(item -> !item.fallback()).toList(), fallback);
    }

    private void seedDefaults() {
        Map<String, MetadataDomain> domains = new LinkedHashMap<>();
        for (DomainSeed seed : domainSeeds()) {
            MetadataDomain domain = domainRepository.findByCodeIgnoreCase(seed.code()).orElseGet(MetadataDomain::new);
            if (domain.getId() == null) {
                domain.setCode(seed.code());
                domain.setName(seed.name());
                domain.setDescription(seed.description());
                domain.setPriority(seed.priority());
                domain.setEnabled(true);
                domain = domainRepository.save(domain);
            }
            domains.put(seed.code(), domain);
        }
        for (ScenarioSeed seed : scenarioSeeds()) {
            MetadataScenario scenario = scenarioRepository.findByCodeIgnoreCase(seed.code()).orElse(null);
            if (scenario == null) {
                scenario = new MetadataScenario();
                scenario.setCode(seed.code());
                scenario.setName(seed.name());
                scenario.setDescription(seed.description());
                scenario.setDomainId(domains.get(seed.domainCode()).getId());
                scenario.setMetadataTypesJson("[]");
                scenario.setPriority(seed.priority());
                scenario.setFallbackScenario(seed.fallback());
                scenario.setEnabled(true);
                scenario = scenarioRepository.save(scenario);
            }
            for (int index = 0; index < seed.keywords().size(); index++) {
                String keyword = seed.keywords().get(index);
                if (termRepository.findByScenarioIdAndNormalizedTerm(
                    scenario.getId(), keyword.toLowerCase(Locale.ROOT)).isPresent()) {
                    continue;
                }
                MetadataTermMapping mapping = new MetadataTermMapping();
                mapping.setScenarioId(scenario.getId());
                mapping.setTerm(keyword);
                mapping.setWeight(BigDecimal.ONE);
                mapping.setMatchType("CONTAINS");
                mapping.setPriority((index + 1) * 10);
                mapping.setEnabled(true);
                termRepository.save(mapping);
            }
        }
    }

    private List<DomainSeed> domainSeeds() {
        return List.of(
            new DomainSeed("common", "公共数据标准", "跨业务域公共参考数据与通用数据标准。", 10),
            new DomainSeed("customer", "客户管理", "客户、投资者、账户与客户关系管理。", 20),
            new DomainSeed("trading", "交易管理", "订单、委托、成交及交易行为。", 30),
            new DomainSeed("financial_market", "金融市场", "证券、基金、债券及衍生产品。", 40),
            new DomainSeed("asset", "资产管理", "资产、资金、持仓、估值与收益。", 50),
            new DomainSeed("risk", "风险合规", "风险、合规、信用及监管管理。", 60),
            new DomainSeed("settlement", "清算结算", "清算、交收、结算及资金对账。", 70),
            new DomainSeed("finance", "财务会计", "财务、会计、凭证与核算。", 80),
            new DomainSeed("organization", "组织治理", "机构、用户、角色与权限治理。", 90)
        );
    }

    private List<ScenarioSeed> scenarioSeeds() {
        return List.of(
            new ScenarioSeed(DEFAULT_SCENARIO, "通用数据标准",
                "通用企业数据标准、字段定义、业务词根和代码字典场景。", "common", 999, true, List.of()),
            new ScenarioSeed("customer_account", "客户与账户",
                "客户主体、投资者、账户开户、客户分层、适当性及客户关系管理。", "customer", 10, false,
                List.of("客户", "投资者", "账户", "开户", "销户", "客户号", "股东", "持有人", "联系人")),
            new ScenarioSeed("trading_order", "交易与订单",
                "委托、成交、撤单、交易流水、交易方向、席位及交易状态。", "trading", 20, false,
                List.of("交易", "委托", "成交", "撤单", "订单", "买入", "卖出", "席位", "报单", "申报")),
            new ScenarioSeed("security_product", "证券与金融产品",
                "证券、基金、债券、股票、衍生品及金融产品基础信息。", "financial_market", 30, false,
                List.of("证券", "基金", "债券", "股票", "产品", "合约", "期权", "期货", "指数", "组合")),
            new ScenarioSeed("asset_position", "资产与持仓",
                "资产余额、资金、持仓、头寸、市值、净值及收益核算。", "asset", 40, false,
                List.of("资产", "余额", "资金", "持仓", "头寸", "市值", "净值", "收益", "盈亏", "可用")),
            new ScenarioSeed("risk_compliance", "风险与合规",
                "风险计量、限额、预警、合规、反洗钱、信用及监管报送。", "risk", 50, false,
                List.of("风险", "限额", "预警", "合规", "反洗钱", "信用", "监管", "敞口", "集中度", "评级")),
            new ScenarioSeed("clearing_settlement", "清算与结算",
                "清算、交收、结算、划款、费用、佣金及资金对账。", "settlement", 60, false,
                List.of("清算", "结算", "交收", "划款", "佣金", "费用", "对账", "结息", "清分")),
            new ScenarioSeed("finance_accounting", "财务与会计",
                "会计科目、凭证、账簿、收入、成本、利润及财务核算。", "finance", 70, false,
                List.of("财务", "会计", "科目", "凭证", "账簿", "收入", "成本", "利润", "税", "核算")),
            new ScenarioSeed("organization_permission", "机构与权限",
                "机构、部门、岗位、员工、用户、角色、权限及渠道管理。", "organization", 80, false,
                List.of("机构", "部门", "岗位", "员工", "用户", "角色", "权限", "渠道", "网点", "经纪人")),
            new ScenarioSeed("reference_dictionary", "公共参考与代码字典",
                "日期、地区、币种、状态、类型、代码和值域等公共参考数据。", "common", 90, false,
                List.of("日期", "时间", "地区", "国家", "币种", "状态", "类型", "代码", "标识", "序号"))
        );
    }

    private MetadataScenario requireScenario(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            throw new IllegalArgumentException("scenarioId is required");
        }
        return scenarioRepository.findById(idOrCode)
            .or(() -> scenarioRepository.findByCodeIgnoreCase(idOrCode))
            .orElseThrow(() -> new IllegalArgumentException("Metadata scenario not found: " + idOrCode));
    }

    private <T> T entity(String id, org.springframework.data.jpa.repository.JpaRepository<T, String> repository,
                         java.util.function.Supplier<T> factory, String label) {
        if (id == null || id.isBlank()) return factory.get();
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException(label + " not found: " + id));
    }

    private String normalizeCode(String value) {
        String code = required(value, "code").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (code.length() > 64) throw new IllegalArgumentException("code is too long");
        return code;
    }

    private String normalizeMatchType(String value) {
        String type = value == null || value.isBlank() ? "CONTAINS" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("EXACT", "CONTAINS", "PREFIX", "TOKEN").contains(type)) {
            throw new IllegalArgumentException("Unsupported matchType: " + type);
        }
        return type;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new IllegalArgumentException("metadataTypes must be a JSON string array", ex);
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record TaxonomySnapshot(List<ScenarioDefinition> scenarios, ScenarioDefinition fallback) {
        public TaxonomySnapshot {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    public record ScenarioDefinition(String id, String code, String name, String description,
                                     String domainId, List<String> metadataTypes, int priority,
                                     boolean fallback, List<TermDefinition> terms) {
        public ScenarioDefinition {
            metadataTypes = metadataTypes == null ? List.of() : List.copyOf(metadataTypes);
            terms = terms == null ? List.of() : List.copyOf(terms);
        }
    }

    public record TermDefinition(String term, String normalizedTerm, double weight,
                                 String matchType, int priority) {
    }

    private record CachedTaxonomy(TaxonomySnapshot snapshot, Instant expiresAt) {
    }

    private record DomainSeed(String code, String name, String description, int priority) {
    }

    private record ScenarioSeed(String code, String name, String description, String domainCode,
                                int priority, boolean fallback, List<String> keywords) {
    }
}
