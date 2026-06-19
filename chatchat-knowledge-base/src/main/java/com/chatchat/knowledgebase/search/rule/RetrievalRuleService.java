package com.chatchat.knowledgebase.search.rule;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalRuleService {

    public static final String TYPE_INTENT = "intent";
    public static final String TYPE_CHUNK = "chunk";
    public static final String TYPE_EXPAND = "expand";
    public static final String TYPE_LEXICON = "lexicon";

    private final QueryIntentRuleRepository intentRuleRepository;
    private final ChunkTypeRuleRepository chunkTypeRuleRepository;
    private final QueryExpandRuleRepository expandRuleRepository;
    private final SemanticLexiconEntryRepository semanticLexiconEntryRepository;
    private final RuleVersionRepository ruleVersionRepository;

    private volatile RuleSnapshot snapshot = RuleSnapshot.empty();

    @PostConstruct
    public void loadRules() {
        initializeDefaultRulesIfNeeded();
        initializeDefaultRuleSupplementsIfNeeded();
        initializeDefaultSemanticLexiconIfNeeded();
        refreshRules();
    }

    @Transactional
    public void initializeDefaultRulesIfNeeded() {
        boolean hasAnyRule = intentRuleRepository.count() > 0
            || chunkTypeRuleRepository.count() > 0
            || expandRuleRepository.count() > 0;
        boolean hasAnyVersion = ruleVersionRepository.count() > 0;
        if (hasAnyRule || hasAnyVersion) {
            return;
        }

        long now = System.currentTimeMillis();
        intentRuleRepository.saveAll(defaultIntentRules(now));
        chunkTypeRuleRepository.saveAll(defaultChunkTypeRules(now));
        expandRuleRepository.saveAll(defaultExpandRules(now));
        ruleVersionRepository.saveAll(List.of(
            activeVersionEntity(TYPE_INTENT, now),
            activeVersionEntity(TYPE_CHUNK, now),
            activeVersionEntity(TYPE_EXPAND, now),
            activeVersionEntity(TYPE_LEXICON, now)
        ));
        log.info("Initialized default retrieval keyword rules");
    }

    @Transactional
    public void initializeDefaultRuleSupplementsIfNeeded() {
        long now = System.currentTimeMillis();
        List<ChunkTypeRuleEntity> missingChunkRules = defaultDomainChunkTypeRules(now).stream()
            .filter(rule -> chunkTypeRuleRepository.findAll().stream()
                .noneMatch(existing -> normalize(existing.getChunkType()).equals(normalize(rule.getChunkType()))))
            .toList();
        if (!missingChunkRules.isEmpty()) {
            chunkTypeRuleRepository.saveAll(missingChunkRules);
        }

        List<QueryExpandRuleEntity> existingExpandRules = expandRuleRepository.findAll();
        List<QueryExpandRuleEntity> missingExpandRules = defaultDomainExpandRules(now).stream()
            .filter(rule -> existingExpandRules.stream().noneMatch(existing ->
                normalize(existing.getSourceWord()).equals(normalize(rule.getSourceWord()))
                    && normalize(existing.getExpandWords()).equals(normalize(rule.getExpandWords()))))
            .toList();
        if (!missingExpandRules.isEmpty()) {
            expandRuleRepository.saveAll(missingExpandRules);
        }
        if (!missingChunkRules.isEmpty() || !missingExpandRules.isEmpty()) {
            log.info("Initialized {} default domain chunk rules and {} default domain expansion rules",
                missingChunkRules.size(), missingExpandRules.size());
        }
    }

    @Transactional
    public void initializeDefaultSemanticLexiconIfNeeded() {
        long now = System.currentTimeMillis();
        List<SemanticLexiconEntryEntity> missingEntries = defaultSemanticLexiconEntries(now).stream()
            .filter(entry -> semanticLexiconEntryRepository
                .findFirstByNormalizedTermAndBuiltinTrue(entry.getNormalizedTerm())
                .isEmpty())
            .toList();
        if (!missingEntries.isEmpty()) {
            semanticLexiconEntryRepository.saveAll(missingEntries);
            log.info("Initialized {} default semantic lexicon entries", missingEntries.size());
        }
        ensureLexiconVersion();
    }

    @Scheduled(fixedDelayString = "${chatchat.search.rule-refresh-ms:30000}")
    public void refreshRules() {
        try {
            int intentVersion = activeVersion(TYPE_INTENT);
            int chunkVersion = activeVersion(TYPE_CHUNK);
            int expandVersion = activeVersion(TYPE_EXPAND);
            int lexiconVersion = activeVersion(TYPE_LEXICON);
            List<IntentRule> intentRules = intentRuleRepository.findByEnabledTrueOrderByPriorityDescUpdatedAtDesc()
                .stream()
                .filter(entity -> defaultInt(entity.getVersion(), 1) == intentVersion)
                .map(this::toIntentRule)
                .filter(Objects::nonNull)
                .toList();
            List<ChunkRule> chunkRules = chunkTypeRuleRepository.findByEnabledTrueOrderByPriorityDescUpdatedAtDesc()
                .stream()
                .filter(entity -> defaultInt(entity.getVersion(), 1) == chunkVersion)
                .map(this::toChunkRule)
                .filter(Objects::nonNull)
                .toList();
            List<ExpandRule> expandRules = expandRuleRepository.findByEnabledTrueOrderByPriorityDescUpdatedAtDesc()
                .stream()
                .filter(entity -> defaultInt(entity.getVersion(), 1) == expandVersion)
                .map(this::toExpandRule)
                .filter(Objects::nonNull)
                .toList();
            List<SemanticLexiconEntry> semanticLexicon = semanticLexiconEntryRepository.findByEnabledTrueOrderByBuiltinAscPriorityDescUpdatedAtDesc()
                .stream()
                .filter(entity -> defaultInt(entity.getVersion(), 1) == lexiconVersion || Boolean.TRUE.equals(entity.getBuiltin()))
                .map(this::toSemanticLexiconEntry)
                .filter(Objects::nonNull)
                .toList();
            this.snapshot = new RuleSnapshot(intentRules, chunkRules, expandRules, semanticLexicon, System.currentTimeMillis());
        } catch (Exception ex) {
            log.warn("Failed to refresh retrieval rules: {}", ex.getMessage(), ex);
        }
    }

    public RuleSnapshot snapshot() {
        return snapshot;
    }

    public List<QueryIntentRuleEntity> listIntentRules() {
        return intentRuleRepository.findAll().stream()
            .sorted(versionedEntityComparator(QueryIntentRuleEntity::getVersion, QueryIntentRuleEntity::getPriority,
                QueryIntentRuleEntity::getUpdatedAt))
            .toList();
    }

    public List<ChunkTypeRuleEntity> listChunkTypeRules() {
        return chunkTypeRuleRepository.findAll().stream()
            .sorted(versionedEntityComparator(ChunkTypeRuleEntity::getVersion, ChunkTypeRuleEntity::getPriority,
                ChunkTypeRuleEntity::getUpdatedAt))
            .toList();
    }

    public List<QueryExpandRuleEntity> listExpandRules() {
        return expandRuleRepository.findAll().stream()
            .sorted(versionedEntityComparator(QueryExpandRuleEntity::getVersion, QueryExpandRuleEntity::getPriority,
                QueryExpandRuleEntity::getUpdatedAt))
            .toList();
    }

    public List<SemanticLexiconEntryEntity> listSemanticLexiconEntries() {
        return semanticLexiconEntryRepository.findAll().stream()
            .sorted(Comparator
                .comparing((SemanticLexiconEntryEntity entity) -> Boolean.TRUE.equals(entity.getBuiltin()))
                .thenComparing(entity -> defaultInt(entity.getVersion(), 1), Comparator.reverseOrder())
                .thenComparing(entity -> defaultInt(entity.getPriority(), 0), Comparator.reverseOrder())
                .thenComparing(entity -> defaultLong(entity.getUpdatedAt(), 0L), Comparator.reverseOrder()))
            .toList();
    }

    public List<RuleVersionEntity> listVersions() {
        return ruleVersionRepository.findAllByOrderByTypeAscVersionDesc();
    }

    public ActiveRuleVersions activeVersions() {
        return new ActiveRuleVersions(
            activeVersion(TYPE_INTENT),
            activeVersion(TYPE_CHUNK),
            activeVersion(TYPE_EXPAND),
            activeVersion(TYPE_LEXICON)
        );
    }

    @Transactional
    public QueryIntentRuleEntity saveIntentRule(QueryIntentRuleEntity request) {
        QueryIntentRuleEntity entity = editableIntentEntity(request);
        entity.setIntent(required(request.getIntent(), "intent"));
        entity.setName(emptyToNull(request.getName()));
        entity.setKeywords(nullToEmpty(request.getKeywords()));
        entity.setRegex(nullToEmpty(request.getRegex()));
        entity.setWeight(defaultInt(request.getWeight(), 1));
        entity.setPriority(defaultInt(request.getPriority(), 0));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        stamp(entity);
        QueryIntentRuleEntity saved = intentRuleRepository.save(entity);
        refreshRules();
        return saved;
    }

    @Transactional
    public ChunkTypeRuleEntity saveChunkTypeRule(ChunkTypeRuleEntity request) {
        ChunkTypeRuleEntity entity = editableChunkEntity(request);
        entity.setChunkType(required(request.getChunkType(), "chunkType"));
        entity.setKeywords(nullToEmpty(request.getKeywords()));
        entity.setPattern(nullToEmpty(request.getPattern()));
        entity.setWeight(defaultInt(request.getWeight(), 1));
        entity.setPriority(defaultInt(request.getPriority(), 0));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        stamp(entity);
        ChunkTypeRuleEntity saved = chunkTypeRuleRepository.save(entity);
        refreshRules();
        return saved;
    }

    @Transactional
    public QueryExpandRuleEntity saveExpandRule(QueryExpandRuleEntity request) {
        QueryExpandRuleEntity entity = editableExpandEntity(request);
        entity.setIntent(emptyToNull(request.getIntent()));
        entity.setSourceWord(emptyToNull(request.getSourceWord()));
        entity.setExpandWords(required(request.getExpandWords(), "expandWords"));
        entity.setWeight(defaultInt(request.getWeight(), 1));
        entity.setPriority(defaultInt(request.getPriority(), 0));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        stamp(entity);
        QueryExpandRuleEntity saved = expandRuleRepository.save(entity);
        refreshRules();
        return saved;
    }

    @Transactional
    public SemanticLexiconEntryEntity saveSemanticLexiconEntry(SemanticLexiconEntryEntity request) {
        SemanticLexiconEntryEntity entity = editableSemanticLexiconEntity(request);
        entity.setTerm(required(request.getTerm(), "term"));
        entity.setNormalizedTerm(normalizeLexiconTerm(request.getTerm()));
        entity.setLanguage(normalizeLanguage(request.getLanguage(), request.getTerm()));
        entity.setMappedTerm(emptyToNull(request.getMappedTerm()));
        entity.setAliases(nullToEmpty(request.getAliases()));
        entity.setCategory(normalizeCategory(request.getCategory()));
        entity.setDomain(normalizeDomain(request.getDomain()));
        entity.setWeight(Math.max(1, defaultInt(request.getWeight(), 1)));
        entity.setPriority(defaultInt(request.getPriority(), Boolean.TRUE.equals(entity.getBuiltin()) ? 10 : 100));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        if (entity.getBuiltin() == null) {
            entity.setBuiltin(false);
        }
        stamp(entity);
        SemanticLexiconEntryEntity saved = semanticLexiconEntryRepository.save(entity);
        refreshRules();
        return saved;
    }

    @Transactional
    public RuleVersionEntity publishLatest(String type) {
        String normalizedType = normalizeRuleType(type);
        int targetVersion = maxVersion(normalizedType);
        RuleVersionEntity version = activateVersion(normalizedType, targetVersion);
        refreshRules();
        return version;
    }

    @Transactional
    public List<RuleVersionEntity> publishAllLatest() {
        List<RuleVersionEntity> versions = List.of(
            activateVersion(TYPE_INTENT, maxVersion(TYPE_INTENT)),
            activateVersion(TYPE_CHUNK, maxVersion(TYPE_CHUNK)),
            activateVersion(TYPE_EXPAND, maxVersion(TYPE_EXPAND)),
            activateVersion(TYPE_LEXICON, maxVersion(TYPE_LEXICON))
        );
        refreshRules();
        return versions;
    }

    @Transactional
    public RuleVersionEntity activateVersion(String type, Integer version) {
        String normalizedType = normalizeRuleType(type);
        int normalizedVersion = Math.max(1, defaultInt(version, 1));
        ruleVersionRepository.findByTypeOrderByVersionDesc(normalizedType)
            .forEach(item -> {
                item.setActive(false);
                item.setUpdatedAt(System.currentTimeMillis());
                ruleVersionRepository.save(item);
            });
        RuleVersionEntity entity = ruleVersionRepository
            .findFirstByTypeAndVersion(normalizedType, normalizedVersion)
            .orElseGet(RuleVersionEntity::new);
        entity.setType(normalizedType);
        entity.setVersion(normalizedVersion);
        entity.setActive(true);
        stamp(entity);
        RuleVersionEntity saved = ruleVersionRepository.save(entity);
        refreshRules();
        return saved;
    }

    @Transactional
    public void deleteIntentRule(Long id) {
        intentRuleRepository.deleteById(id);
        refreshRules();
    }

    @Transactional
    public void deleteChunkTypeRule(Long id) {
        chunkTypeRuleRepository.deleteById(id);
        refreshRules();
    }

    @Transactional
    public void deleteExpandRule(Long id) {
        expandRuleRepository.deleteById(id);
        refreshRules();
    }

    @Transactional
    public void deleteSemanticLexiconEntry(Long id) {
        SemanticLexiconEntryEntity entity = semanticLexiconEntryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("semantic lexicon entry not found"));
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new IllegalArgumentException("default semantic lexicon entries cannot be deleted");
        }
        semanticLexiconEntryRepository.deleteById(id);
        refreshRules();
    }

    private IntentRule toIntentRule(QueryIntentRuleEntity entity) {
        List<String> keywords = splitWords(entity.getKeywords());
        Pattern pattern = compilePattern(entity.getRegex());
        if (isBlank(entity.getIntent()) || (keywords.isEmpty() && pattern == null)) {
            return null;
        }
        return new IntentRule(
            normalize(entity.getIntent()),
            keywords,
            pattern,
            Math.max(1, defaultInt(entity.getWeight(), 1)),
            defaultInt(entity.getPriority(), 0)
        );
    }

    private ChunkRule toChunkRule(ChunkTypeRuleEntity entity) {
        List<String> keywords = splitWords(entity.getKeywords());
        Pattern pattern = compilePattern(entity.getPattern());
        if (isBlank(entity.getChunkType()) || (keywords.isEmpty() && pattern == null)) {
            return null;
        }
        return new ChunkRule(
            normalize(entity.getChunkType()),
            keywords,
            pattern,
            Math.max(1, defaultInt(entity.getWeight(), 1)),
            defaultInt(entity.getPriority(), 0)
        );
    }

    private ExpandRule toExpandRule(QueryExpandRuleEntity entity) {
        List<String> expandWords = splitWords(entity.getExpandWords());
        if (expandWords.isEmpty()) {
            return null;
        }
        return new ExpandRule(
            normalize(entity.getIntent()),
            normalize(entity.getSourceWord()),
            expandWords,
            Math.max(1, defaultInt(entity.getWeight(), 1)),
            defaultInt(entity.getPriority(), 0)
        );
    }

    private SemanticLexiconEntry toSemanticLexiconEntry(SemanticLexiconEntryEntity entity) {
        String term = normalizeLexiconTerm(entity.getTerm());
        if (term.isBlank()) {
            return null;
        }
        Set<String> expansions = new LinkedHashSet<>();
        addLexiconValue(expansions, entity.getMappedTerm());
        splitWords(entity.getAliases()).forEach(expansions::add);
        expansions.remove(term);
        return new SemanticLexiconEntry(
            term,
            normalize(entity.getLanguage()),
            normalize(entity.getMappedTerm()),
            new ArrayList<>(expansions),
            normalizeCategory(entity.getCategory()),
            normalizeDomain(entity.getDomain()),
            Math.max(1, defaultInt(entity.getWeight(), 1)),
            defaultInt(entity.getPriority(), 0),
            Boolean.TRUE.equals(entity.getBuiltin())
        );
    }

    private Pattern compilePattern(String pattern) {
        if (isBlank(pattern)) {
            return null;
        }
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException ex) {
            log.warn("Ignoring invalid retrieval rule regex '{}': {}", pattern, ex.getMessage());
            return null;
        }
    }

    private List<String> splitWords(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        Set<String> words = new LinkedHashSet<>();
        Arrays.stream(value.split("[,;\\r\\n\\t]+"))
            .map(String::trim)
            .filter(text -> !text.isBlank())
            .map(this::normalize)
            .forEach(words::add);
        return new ArrayList<>(words);
    }

    private void stamp(QueryIntentRuleEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private void stamp(ChunkTypeRuleEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private void stamp(QueryExpandRuleEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private void stamp(SemanticLexiconEntryEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private void stamp(RuleVersionEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private QueryIntentRuleEntity editableIntentEntity(QueryIntentRuleEntity request) {
        int targetVersion = targetVersion(TYPE_INTENT, request.getVersion());
        QueryIntentRuleEntity entity = request.getId() == null
            ? new QueryIntentRuleEntity()
            : intentRuleRepository.findById(request.getId()).orElseGet(QueryIntentRuleEntity::new);
        if (request.getId() != null && defaultInt(entity.getVersion(), 1) != targetVersion) {
            entity = new QueryIntentRuleEntity();
        } else {
            entity.setId(request.getId());
        }
        entity.setVersion(targetVersion);
        return entity;
    }

    private ChunkTypeRuleEntity editableChunkEntity(ChunkTypeRuleEntity request) {
        int targetVersion = targetVersion(TYPE_CHUNK, request.getVersion());
        ChunkTypeRuleEntity entity = request.getId() == null
            ? new ChunkTypeRuleEntity()
            : chunkTypeRuleRepository.findById(request.getId()).orElseGet(ChunkTypeRuleEntity::new);
        if (request.getId() != null && defaultInt(entity.getVersion(), 1) != targetVersion) {
            entity = new ChunkTypeRuleEntity();
        } else {
            entity.setId(request.getId());
        }
        entity.setVersion(targetVersion);
        return entity;
    }

    private QueryExpandRuleEntity editableExpandEntity(QueryExpandRuleEntity request) {
        int targetVersion = targetVersion(TYPE_EXPAND, request.getVersion());
        QueryExpandRuleEntity entity = request.getId() == null
            ? new QueryExpandRuleEntity()
            : expandRuleRepository.findById(request.getId()).orElseGet(QueryExpandRuleEntity::new);
        if (request.getId() != null && defaultInt(entity.getVersion(), 1) != targetVersion) {
            entity = new QueryExpandRuleEntity();
        } else {
            entity.setId(request.getId());
        }
        entity.setVersion(targetVersion);
        return entity;
    }

    private SemanticLexiconEntryEntity editableSemanticLexiconEntity(SemanticLexiconEntryEntity request) {
        SemanticLexiconEntryEntity entity = request.getId() == null
            ? new SemanticLexiconEntryEntity()
            : semanticLexiconEntryRepository.findById(request.getId()).orElseGet(SemanticLexiconEntryEntity::new);
        boolean builtin = Boolean.TRUE.equals(entity.getBuiltin());
        int targetVersion = builtin ? 1 : targetVersion(TYPE_LEXICON, request.getVersion());
        if (request.getId() != null && !builtin && defaultInt(entity.getVersion(), 1) != targetVersion) {
            entity = new SemanticLexiconEntryEntity();
        } else {
            entity.setId(request.getId());
        }
        entity.setBuiltin(builtin);
        entity.setVersion(targetVersion);
        return entity;
    }

    private int targetVersion(String type, Integer requestedVersion) {
        return requestedVersion == null ? draftVersion(type) : Math.max(1, requestedVersion);
    }

    private int draftVersion(String type) {
        int active = activeVersion(type);
        int max = maxVersion(type);
        return max > active ? max : active + 1;
    }

    private int activeVersion(String type) {
        String normalizedType = normalizeRuleType(type);
        return ruleVersionRepository.findFirstByTypeAndActiveTrueOrderByVersionDesc(normalizedType)
            .map(RuleVersionEntity::getVersion)
            .orElse(1);
    }

    private int maxVersion(String type) {
        String normalizedType = normalizeRuleType(type);
        return switch (normalizedType) {
            case TYPE_INTENT -> intentRuleRepository.findAll().stream()
                .map(QueryIntentRuleEntity::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
            case TYPE_CHUNK -> chunkTypeRuleRepository.findAll().stream()
                .map(ChunkTypeRuleEntity::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
            case TYPE_EXPAND -> expandRuleRepository.findAll().stream()
                .map(QueryExpandRuleEntity::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
            case TYPE_LEXICON -> semanticLexiconEntryRepository.findAll().stream()
                .map(SemanticLexiconEntryEntity::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(1);
            default -> 1;
        };
    }

    private String normalizeRuleType(String type) {
        String value = normalize(type);
        if (TYPE_INTENT.equals(value) || TYPE_CHUNK.equals(value) || TYPE_EXPAND.equals(value)
            || TYPE_LEXICON.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported rule type: " + type);
    }

    private <T> Comparator<T> entityComparator(ValueExtractor<T, Integer> priorityExtractor,
                                               ValueExtractor<T, Long> updatedAtExtractor) {
        return Comparator
            .<T, Integer>comparing(entity -> defaultInt(priorityExtractor.get(entity), 0), Comparator.reverseOrder())
            .thenComparing(entity -> defaultLong(updatedAtExtractor.get(entity), 0L), Comparator.reverseOrder());
    }

    private <T> Comparator<T> versionedEntityComparator(ValueExtractor<T, Integer> versionExtractor,
                                                        ValueExtractor<T, Integer> priorityExtractor,
                                                        ValueExtractor<T, Long> updatedAtExtractor) {
        return Comparator
            .<T, Integer>comparing(entity -> defaultInt(versionExtractor.get(entity), 1), Comparator.reverseOrder())
            .thenComparing(entity -> defaultInt(priorityExtractor.get(entity), 0), Comparator.reverseOrder())
            .thenComparing(entity -> defaultLong(updatedAtExtractor.get(entity), 0L), Comparator.reverseOrder());
    }

    private String required(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private long defaultLong(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeLexiconTerm(String value) {
        return normalize(value);
    }

    private String normalizeLanguage(String requested, String term) {
        String language = normalize(requested);
        if ("zh".equals(language) || "en".equals(language) || "bilingual".equals(language)) {
            return language;
        }
        return term != null && Pattern.compile("\\p{IsHan}").matcher(term).find() ? "zh" : "en";
    }

    private String normalizeCategory(String value) {
        String category = normalize(value);
        return category.isBlank() ? "general" : category;
    }

    private String normalizeDomain(String value) {
        String domain = normalize(value);
        return domain.isBlank() ? "general" : domain;
    }

    private void addLexiconValue(Set<String> target, String value) {
        splitWords(value).forEach(target::add);
    }

    private List<QueryIntentRuleEntity> defaultIntentRules(long now) {
        return List.of(
            intentRule("TROUBLESHOOTING", "Troubleshooting Intent",
                "错误,异常,失败,故障,报错,无法,不能,超时,重试,error,exception,fail,failure,timeout,retry,issue",
                5, 100, now),
            intentRule("HOW_TO", "How-to Intent",
                "如何,怎么,怎样,步骤,配置,设置,安装,接入,使用,教程,指南,how,configure,setup,install,guide,step",
                4, 90, now),
            intentRule("DATA_ISSUE", "Data Issue Intent",
                "数据,报表,统计,指标,同步,缺失,不一致,异常值,口径,data,report,metric,statistics,sync,missing,mismatch",
                4, 80, now),
            intentRule("POLICY", "Policy Intent",
                "制度,规范,规则,权限,审批,合规,流程,policy,permission,approval,compliance,process,role",
                3, 70, now),
            intentRule("FAQ", "FAQ Intent",
                "是什么,为什么,说明,介绍,含义,区别,faq,what,why,explain,definition,meaning,difference",
                3, 60, now)
        );
    }

    private List<ChunkTypeRuleEntity> defaultChunkTypeRules(long now) {
        return List.of(
            chunkRule("troubleshooting", "错误,异常,失败,故障,排查,原因,解决,修复,error,exception,failure,root cause,fix,retry", 5, 100, now),
            chunkRule("step", "步骤,第一步,第二步,操作,配置,安装,流程,step,procedure,configure,setup,install", 4, 90, now),
            chunkRule("definition", "定义,是什么,说明,概念,含义,definition,overview,description,meaning", 3, 80, now),
            chunkRule("policy", "制度,规范,规则,权限,审批,合规,policy,permission,approval,compliance", 3, 70, now),
            chunkRule("example", "示例,例子,样例,案例,example,sample,case", 2, 60, now),
            chunkRule("table", "表格,字段,列,行,统计,清单,table,column,row,sheet,list", 2, 50, now),
            chunkRule("log", "日志,trace,debug,warn,error,stack,exception,log", 2, 40, now)
        );
    }

    private List<QueryExpandRuleEntity> defaultExpandRules(long now) {
        return List.of(
            expandRule("TROUBLESHOOTING", "登录", "login,signin,auth,token,session,账号,认证", 4, 100, now),
            expandRule("TROUBLESHOOTING", "错误", "error,exception,failure,fail,异常,失败,报错", 4, 95, now),
            expandRule("TROUBLESHOOTING", "超时", "timeout,slow,retry,latency,延迟,重试", 3, 90, now),
            expandRule("HOW_TO", "配置", "configure,setup,setting,install,guide,步骤,设置,安装", 3, 85, now),
            expandRule("DATA_ISSUE", "报表", "report,statistics,metric,data,统计,指标,数据", 3, 80, now),
            expandRule("POLICY", "权限", "permission,role,access,auth,approval,角色,访问,审批", 3, 75, now),
            expandRule("FAQ", "区别", "difference,compare,versus,对比,不同,差异", 2, 60, now)
        );
    }

    private List<QueryExpandRuleEntity> defaultDomainExpandRules(long now) {
        return List.of(
            expandRule("DATA_ISSUE", "sql", "query,select,database,table,spark sql", 2, 65, now),
            expandRule("DATA_ISSUE", "ddl", "create,alter,drop,database,table,view,function", 2, 64, now),
            expandRule("DATA_ISSUE", "dml", "insert,update,delete,load data,truncate", 2, 63, now),
            expandRule("DATA_ISSUE", "spark", "distributed,cluster,shuffle,partition,repartition", 2, 62, now),
            expandRule("DATA_ISSUE", "connector", "jdbc,filesystem,mongodb,hbase,elasticsearch,file source,database connector", 2, 61, now),
            expandRule("DATA_ISSUE", "ingestion", "load data,import,etl,file ingestion,file input", 2, 60, now),
            expandRule("DATA_ISSUE", "search", "lucene,fulltext,full text,index,retrieval,inverted index", 2, 59, now),
            expandRule("DATA_ISSUE", "retrieval", "document search,scoring,bm25,lucene,index", 2, 58, now)
        );
    }

    private List<ChunkTypeRuleEntity> defaultDomainChunkTypeRules(long now) {
        return List.of(
            chunkRule("sql_chunk", "create table,alter table,insert into,select query,group by,join,window function,cte", 3, 65, now),
            chunkRule("connector_chunk", "jdbc,filesystem,elasticsearch,hbase,mongodb,ftp,mysql connector,file source", 3, 64, now),
            chunkRule("spark_chunk", "spark sql,partition,shuffle,cluster by,distribute by,repartition", 3, 63, now),
            chunkRule("search_chunk", "lucene,index,full text,bm25,inverted index,retrieval,document search", 3, 62, now)
        );
    }

    private List<SemanticLexiconEntryEntity> defaultSemanticLexiconEntries(long now) {
        return List.of(
            semanticLexiconEntry("营收", "zh", "revenue", "收入,营业收入,sales,turnover", "metric", "finance", 2, now),
            semanticLexiconEntry("利润", "zh", "profit", "净利润,earnings,net income", "metric", "finance", 2, now),
            semanticLexiconEntry("毛利率", "zh", "gross margin", "毛利,gross margin,gross profit margin", "metric", "finance", 2, now),
            semanticLexiconEntry("资产负债率", "zh", "debt ratio", "负债率,leverage,debt to asset", "metric", "finance", 2, now),
            semanticLexiconEntry("现金流", "zh", "cash flow", "经营现金流,operating cash flow,free cash flow", "metric", "finance", 2, now),
            semanticLexiconEntry("同比", "zh", "year over year", "yoy,year-on-year,yearly growth", "time", "finance", 2, now),
            semanticLexiconEntry("环比", "zh", "period over period", "mom,qoq,month over month,quarter over quarter", "time", "finance", 2, now),
            semanticLexiconEntry("资产管理规模", "zh", "assets under management", "aum,管理规模", "metric", "finance", 2, now),
            semanticLexiconEntry("客户", "zh", "customer", "client,用户,客群", "entity", "finance", 1, now),
            semanticLexiconEntry("订单", "zh", "order", "transaction,交易,单据", "entity", "finance", 1, now),
            semanticLexiconEntry("指标", "zh", "metric", "measure,kpi,关键指标", "metric", "data", 2, now),
            semanticLexiconEntry("口径", "zh", "definition", "统计口径,calculation logic,caliber", "definition", "data", 2, now),
            semanticLexiconEntry("字段", "zh", "field", "column,列,字段名", "schema", "data", 1, now),
            semanticLexiconEntry("表", "zh", "table", "dataset,数据表,sheet", "schema", "data", 1, now),
            semanticLexiconEntry("同步", "zh", "sync", "synchronize,replicate,数据同步", "operation", "data", 1, now),
            semanticLexiconEntry("延迟", "zh", "latency", "delay,lag,时延", "quality", "data", 1, now),
            semanticLexiconEntry("异常值", "zh", "outlier", "anomaly,abnormal value", "quality", "data", 1, now),
            semanticLexiconEntry("缺失值", "zh", "missing value", "null,空值,blank", "quality", "data", 1, now),
            semanticLexiconEntry("revenue", "en", "营收", "sales,turnover,收入,营业收入", "metric", "finance", 2, now),
            semanticLexiconEntry("metric", "en", "指标", "measure,kpi,关键指标", "metric", "data", 2, now),
            semanticLexiconEntry("数据库定义", "zh", "create database", "ddl_database,创建数据库,修改数据库,删除数据库,alter database,drop database", "ddl_database", "sql", 2, now),
            semanticLexiconEntry("表定义", "zh", "create table", "ddl_table,创建表,修改表,删除表,alter table,drop table", "ddl_table", "sql", 2, now),
            semanticLexiconEntry("视图定义", "zh", "create view", "ddl_view,创建视图,修改视图,删除视图,alter view,drop view", "ddl_view", "sql", 2, now),
            semanticLexiconEntry("函数定义", "zh", "create function", "ddl_function,创建函数,删除函数,drop function", "ddl_function", "sql", 2, now),
            semanticLexiconEntry("插入数据", "zh", "insert into", "insert_data,写入数据,导入数据,data insert,data load", "insert_data", "sql", 2, now),
            semanticLexiconEntry("清空表", "zh", "truncate table", "truncate_data,截断数据,delete all rows", "truncate_data", "sql", 2, now),
            semanticLexiconEntry("数据加载", "zh", "load data", "load_data,文件导入,file ingestion", "load_data", "sql", 2, now),
            semanticLexiconEntry("查询数据", "zh", "select query", "select_query,数据检索,data retrieval", "select_query", "sql", 2, now),
            semanticLexiconEntry("聚合", "zh", "aggregation", "aggregation,分组统计,group by,sum,count,avg", "aggregation", "sql", 2, now),
            semanticLexiconEntry("关联查询", "zh", "join", "join_operation,表连接,inner join,left join", "join_operation", "sql", 2, now),
            semanticLexiconEntry("公共表表达式", "zh", "cte", "cte,临时结果集,common table expression,with clause", "cte", "sql", 2, now),
            semanticLexiconEntry("窗口函数", "zh", "window function", "window_function,分析函数,over partition", "window_function", "sql", 2, now),
            semanticLexiconEntry("spark sql", "bilingual", "structured data processing", "spark_sql,分布式sql引擎,distributed sql engine", "spark_sql", "spark", 2, now),
            semanticLexiconEntry("分区", "zh", "partition", "partitioning,数据分片,distribute by,cluster by", "partitioning", "spark", 2, now),
            semanticLexiconEntry("排序策略", "zh", "sort by", "sorting_strategy,order by,cluster by", "sorting_strategy", "spark", 1, now),
            semanticLexiconEntry("数据重分区", "zh", "shuffle", "shuffle,repartition,数据重分布", "shuffle", "spark", 2, now),
            semanticLexiconEntry("文件系统连接器", "zh", "filesystem connector", "filesystem_connector,本地文件读取,分布式文件读取,file source", "filesystem_connector", "data_source", 2, now),
            semanticLexiconEntry("文本格式", "zh", "text format", "text_file_format,csv文件,csv file", "text_file_format", "data_format", 1, now),
            semanticLexiconEntry("文件数据导入", "zh", "file ingestion", "file_ingestion,file input,文件导入", "file_ingestion", "data_pipeline", 2, now),
            semanticLexiconEntry("jdbc连接器", "bilingual", "database connector", "jdbc_connector,数据库连接,jdbc connector", "jdbc_connector", "database", 2, now),
            semanticLexiconEntry("mysql数据源", "bilingual", "mysql source", "mysql_source,relational database source,关系型数据库源", "mysql_source", "database", 2, now),
            semanticLexiconEntry("ssl加密连接", "bilingual", "ssl connection", "ssl_connection,secure jdbc", "ssl_connection", "security", 1, now),
            semanticLexiconEntry("lucene搜索连接器", "bilingual", "lucene connector", "lucene_connector,索引检索,inverted index search", "lucene_connector", "search", 2, now),
            semanticLexiconEntry("全文检索", "zh", "full text search", "full_text_search,fulltext search", "full_text_search", "search", 2, now),
            semanticLexiconEntry("mongodb连接器", "bilingual", "mongodb connector", "mongodb_connector,文档数据库,document database", "mongodb_connector", "nosql", 2, now),
            semanticLexiconEntry("文档存储", "zh", "document store", "document_store,document database", "document_store", "nosql", 1, now),
            semanticLexiconEntry("hbase连接器", "bilingual", "hbase connector", "hbase_connector,列存储数据库,column family store", "hbase_connector", "bigdata", 2, now),
            semanticLexiconEntry("行键", "zh", "rowkey", "rowkey,primary key in hbase", "rowkey", "bigdata", 1, now),
            semanticLexiconEntry("elasticsearch连接器", "bilingual", "elasticsearch connector", "elasticsearch_connector,es搜索引擎连接器,search engine sink", "elasticsearch_connector", "search", 2, now),
            semanticLexiconEntry("倒排索引", "zh", "inverted index", "inverted_index", "inverted_index", "search", 2, now),
            semanticLexiconEntry("ftp文件采集连接器", "bilingual", "ftp connector", "ftp_connector,file transfer ingestion", "ftp_connector", "data_ingestion", 1, now),
            semanticLexiconEntry("hudi数据湖表", "bilingual", "hudi table", "hudi_connector,incremental data lake", "hudi_connector", "lakehouse", 1, now),
            semanticLexiconEntry("iceberg数据湖表", "bilingual", "iceberg table", "iceberg_connector,table format for lakehouse", "iceberg_connector", "lakehouse", 1, now),
            semanticLexiconEntry("图数据库", "zh", "graph database", "graph_database,neo4j", "graph_database", "graph", 1, now),
            semanticLexiconEntry("节点关系", "zh", "node relationship", "node_relationship,graph edge", "node_relationship", "graph", 1, now),
            semanticLexiconEntry("olap分析引擎", "bilingual", "analytical database", "olap_engine,doris,olap engine", "olap_engine", "olap", 1, now),
            semanticLexiconEntry("宽列存储", "zh", "wide column database", "wide_column_store,cassandra,wide column store", "wide_column_store", "nosql", 1, now)
        );
    }

    private QueryIntentRuleEntity intentRule(String intent, String name, String keywords,
                                             int weight, int priority, long now) {
        QueryIntentRuleEntity entity = new QueryIntentRuleEntity();
        entity.setIntent(intent);
        entity.setName(name);
        entity.setKeywords(keywords);
        entity.setRegex("");
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private ChunkTypeRuleEntity chunkRule(String chunkType, String keywords, int weight, int priority, long now) {
        ChunkTypeRuleEntity entity = new ChunkTypeRuleEntity();
        entity.setChunkType(chunkType);
        entity.setKeywords(keywords);
        entity.setPattern("");
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private QueryExpandRuleEntity expandRule(String intent, String sourceWord, String expandWords,
                                             int weight, int priority, long now) {
        QueryExpandRuleEntity entity = new QueryExpandRuleEntity();
        entity.setIntent(intent);
        entity.setSourceWord(sourceWord);
        entity.setExpandWords(expandWords);
        entity.setWeight(weight);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private SemanticLexiconEntryEntity semanticLexiconEntry(String term,
                                                            String language,
                                                            String mappedTerm,
                                                            String aliases,
                                                            String category,
                                                            String domain,
                                                            int weight,
                                                            long now) {
        SemanticLexiconEntryEntity entity = new SemanticLexiconEntryEntity();
        entity.setTerm(term);
        entity.setNormalizedTerm(normalizeLexiconTerm(term));
        entity.setLanguage(language);
        entity.setMappedTerm(mappedTerm);
        entity.setAliases(aliases);
        entity.setCategory(category);
        entity.setDomain(domain);
        entity.setWeight(weight);
        entity.setPriority(10);
        entity.setBuiltin(true);
        entity.setEnabled(true);
        entity.setVersion(1);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private RuleVersionEntity activeVersionEntity(String type, long now) {
        RuleVersionEntity entity = new RuleVersionEntity();
        entity.setType(type);
        entity.setVersion(1);
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void ensureLexiconVersion() {
        if (ruleVersionRepository.findFirstByTypeAndActiveTrueOrderByVersionDesc(TYPE_LEXICON).isPresent()) {
            return;
        }
        RuleVersionEntity entity = ruleVersionRepository
            .findFirstByTypeAndVersion(TYPE_LEXICON, 1)
            .orElseGet(RuleVersionEntity::new);
        entity.setType(TYPE_LEXICON);
        entity.setVersion(1);
        entity.setActive(true);
        stamp(entity);
        ruleVersionRepository.save(entity);
    }

    @FunctionalInterface
    private interface ValueExtractor<T, R> {
        R get(T value);
    }

    public record RuleSnapshot(
        List<IntentRule> intentRules,
        List<ChunkRule> chunkRules,
        List<ExpandRule> expandRules,
        List<SemanticLexiconEntry> semanticLexicon,
        long refreshedAt
    ) {
        static RuleSnapshot empty() {
            return new RuleSnapshot(List.of(), List.of(), List.of(), List.of(), 0L);
        }
    }

    public record IntentRule(
        String intent,
        List<String> keywords,
        Pattern pattern,
        int weight,
        int priority
    ) {
    }

    public record ChunkRule(
        String chunkType,
        List<String> keywords,
        Pattern pattern,
        int weight,
        int priority
    ) {
    }

    public record ExpandRule(
        String intent,
        String sourceWord,
        List<String> expandWords,
        int weight,
        int priority
    ) {
    }

    public record SemanticLexiconEntry(
        String term,
        String language,
        String mappedTerm,
        List<String> aliases,
        String category,
        String domain,
        int weight,
        int priority,
        boolean builtin
    ) {
    }

    public record ActiveRuleVersions(
        int intentVersion,
        int chunkVersion,
        int expandVersion,
        int lexiconVersion
    ) {
    }
}
