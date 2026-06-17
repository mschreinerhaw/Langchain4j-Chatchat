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

    private final QueryIntentRuleRepository intentRuleRepository;
    private final ChunkTypeRuleRepository chunkTypeRuleRepository;
    private final QueryExpandRuleRepository expandRuleRepository;
    private final RuleVersionRepository ruleVersionRepository;

    private volatile RuleSnapshot snapshot = RuleSnapshot.empty();

    @PostConstruct
    public void loadRules() {
        initializeDefaultRulesIfNeeded();
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
            activeVersionEntity(TYPE_EXPAND, now)
        ));
        log.info("Initialized default retrieval keyword rules");
    }

    @Scheduled(fixedDelayString = "${chatchat.search.rule-refresh-ms:30000}")
    public void refreshRules() {
        try {
            int intentVersion = activeVersion(TYPE_INTENT);
            int chunkVersion = activeVersion(TYPE_CHUNK);
            int expandVersion = activeVersion(TYPE_EXPAND);
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
            this.snapshot = new RuleSnapshot(intentRules, chunkRules, expandRules, System.currentTimeMillis());
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

    public List<RuleVersionEntity> listVersions() {
        return ruleVersionRepository.findAllByOrderByTypeAscVersionDesc();
    }

    public ActiveRuleVersions activeVersions() {
        return new ActiveRuleVersions(
            activeVersion(TYPE_INTENT),
            activeVersion(TYPE_CHUNK),
            activeVersion(TYPE_EXPAND)
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
            activateVersion(TYPE_EXPAND, maxVersion(TYPE_EXPAND))
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
            default -> 1;
        };
    }

    private String normalizeRuleType(String type) {
        String value = normalize(type);
        if (TYPE_INTENT.equals(value) || TYPE_CHUNK.equals(value) || TYPE_EXPAND.equals(value)) {
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

    private RuleVersionEntity activeVersionEntity(String type, long now) {
        RuleVersionEntity entity = new RuleVersionEntity();
        entity.setType(type);
        entity.setVersion(1);
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    @FunctionalInterface
    private interface ValueExtractor<T, R> {
        R get(T value);
    }

    public record RuleSnapshot(
        List<IntentRule> intentRules,
        List<ChunkRule> chunkRules,
        List<ExpandRule> expandRules,
        long refreshedAt
    ) {
        static RuleSnapshot empty() {
            return new RuleSnapshot(List.of(), List.of(), List.of(), 0L);
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

    public record ActiveRuleVersions(
        int intentVersion,
        int chunkVersion,
        int expandVersion
    ) {
    }
}
