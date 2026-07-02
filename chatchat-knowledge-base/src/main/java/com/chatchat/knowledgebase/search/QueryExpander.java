package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class QueryExpander {

    private static final List<BilingualRetrievalEntry> BILINGUAL_RETRIEVAL_ENTRIES = List.of(
        entry(List.of("服务器", "主机", "节点", "server", "host", "node", "machine"), List.of("服务器", "主机", "节点", "server", "host", "node", "machine")),
        entry(List.of("清单", "列表", "目录", "list", "inventory", "catalog", "roster"), List.of("清单", "列表", "目录", "list", "inventory", "catalog", "roster")),
        entry(List.of("配置", "参数", "configuration", "config", "setting", "settings"), List.of("配置", "参数", "configuration", "config", "setting", "settings")),
        entry(List.of("部署", "发布", "上线", "deployment", "deploy", "release", "rollout"), List.of("部署", "发布", "上线", "deployment", "deploy", "release", "rollout")),
        entry(List.of("软件", "software", "package", "packages"), List.of("软件", "software", "package", "packages")),
        entry(List.of("硬件", "hardware", "cpu", "memory", "disk"), List.of("硬件", "cpu", "内存", "磁盘", "hardware", "memory", "disk")),
        entry(List.of("数据", "data", "dataset"), List.of("数据", "数据集", "data", "dataset")),
        entry(List.of("资产", "asset", "assets"), List.of("资产", "asset", "assets")),
        entry(List.of("管理", "治理", "management", "governance"), List.of("管理", "治理", "management", "governance")),
        entry(List.of("平台", "platform"), List.of("平台", "platform")),
        entry(List.of("指标", "度量", "metric", "metrics", "indicator"), List.of("指标", "度量", "metric", "metrics", "indicator")),
        entry(List.of("报表", "报告", "report", "dashboard"), List.of("报表", "报告", "report", "dashboard")),
        entry(List.of("告警", "提醒", "通知", "alert", "alarm", "notification", "notice"), List.of("告警", "提醒", "通知", "alert", "alarm", "notification", "notice")),
        entry(List.of("异常", "错误", "故障", "exception", "error", "failure", "anomaly"), List.of("异常", "错误", "故障", "exception", "error", "failure", "anomaly")),
        entry(List.of("行情", "市场", "报价", "market", "quote", "price"), List.of("行情", "市场", "报价", "market", "quote", "price")),
        entry(List.of("权限", "认证", "角色", "permission", "auth", "authentication", "role"), List.of("权限", "认证", "角色", "permission", "auth", "authentication", "role")),
        entry(List.of("用户", "user", "account"), List.of("用户", "账号", "user", "account")),
        entry(List.of("数据库", "database", "db"), List.of("数据库", "database", "db")),
        entry(List.of("表结构", "数据表", "table", "schema"), List.of("表结构", "数据表", "table", "schema")),
        entry(List.of("字段", "数据列", "field", "column"), List.of("字段", "数据列", "field", "column")),
        entry(List.of("接口", "api", "interface", "endpoint"), List.of("接口", "api", "interface", "endpoint")),
        entry(List.of("服务", "service"), List.of("服务", "service")),
        entry(List.of("集群", "cluster"), List.of("集群", "cluster")),
        entry(List.of("服务器清单", "server list", "server inventory", "host inventory"), List.of("服务器清单", "主机清单", "server list", "server inventory", "host inventory")),
        entry(List.of("软件部署", "software deployment", "deployment list"), List.of("软件部署", "部署清单", "software deployment", "deployment list")),
        entry(List.of("推荐配置", "recommended configuration", "recommended config"), List.of("推荐配置", "recommended configuration", "recommended config")),
        entry(List.of("sparksql", "spark sql", "spark-sql", "spark_sql"), List.of("sparksql", "spark sql", "spark-sql", "spark_sql", "spark", "sql"))
    );

    private final SearchTokenizer tokenizer;
    private final QueryIntentClassifier intentClassifier;
    private final RetrievalRuleService ruleService;

    public List<String> expandTokens(List<String> tokens) {
        return expandTokens(tokens, QueryIntent.GENERAL);
    }

    public List<String> expandTokens(List<String> tokens, QueryIntent intent) {
        return expandTokens(tokens, intent == null ? QueryIntent.GENERAL.name() : intent.name(), "");
    }

    public List<String> expandTokens(List<String> tokens, String intentName, String query) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String token : tokens) {
            addToken(expanded, token);
        }
        String normalizedQuery = normalize(query);
        applyBilingualRetrieval(expanded, normalizedQuery, tokens);
        applySemanticLexicon(expanded, normalizedQuery, tokens);
        for (RetrievalRuleService.ExpandRule rule : ruleService.snapshot().expandRules()) {
            if (!intentMatches(rule.intent(), intentName) || !sourceMatches(rule.sourceWord(), normalizedQuery, tokens)) {
                continue;
            }
            for (int i = 0; i < rule.weight(); i++) {
                for (String expandWord : rule.expandWords()) {
                    addToken(expanded, expandWord);
                }
            }
        }
        return new ArrayList<>(expanded);
    }

    public List<String> expandQuery(String query) {
        String normalizedQuery = normalizeQuery(query);
        List<String> tokens = tokenizer.searchTokens(normalizedQuery);
        return expandTokens(tokens, intentClassifier.classifyName(normalizedQuery, tokens), normalizedQuery);
    }

    public QueryIntent classifyIntent(String query) {
        return intentClassifier.classify(normalizeQuery(query));
    }

    public String classifyIntentName(String query) {
        return intentClassifier.classifyName(normalizeQuery(query));
    }

    public String rewriteQuery(String query) {
        return String.join(" ", expandQuery(query));
    }

    public String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return "";
        }
        normalized = focusRetrievalQuery(normalized);
        Set<String> appended = new LinkedHashSet<>();
        String normalizedText = normalize(normalized);
        List<String> baseTokens = tokenizer.searchTokens(normalizedText);
        for (BilingualRetrievalEntry entry : BILINGUAL_RETRIEVAL_ENTRIES) {
            if (!bilingualEntryMatches(entry, normalizedText, baseTokens)) {
                continue;
            }
            for (String value : entry.expansions()) {
                addRawLexiconValue(appended, value, normalizedText);
            }
        }
        for (RetrievalRuleService.SemanticLexiconEntry entry : ruleService.snapshot().semanticLexicon()) {
            if (!lexiconMatches(entry, normalizedText, baseTokens)) {
                continue;
            }
            addRawLexiconValue(appended, entry.term(), normalizedText);
            addRawLexiconValue(appended, entry.mappedTerm(), normalizedText);
        }
        if (appended.isEmpty()) {
            return normalized;
        }
        return normalized + " " + String.join(" ", appended);
    }

    private String focusRetrievalQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.isBlank() || !looksLikeRetrievalQuestion(value)) {
            return value;
        }
        String focused = value
            .replaceAll("(?i)\\b(do|does|did)\\s+(we|you)\\s+(have|keep|own)\\b", " ")
            .replaceAll("(?i)\\b(is|are)\\s+there\\b", " ")
            .replaceAll("(?i)\\b(can|could|please)\\s+(you\\s+)?(help\\s+)?(find|search|lookup|look\\s+up|retrieve|check)\\b", " ")
            .replaceAll("(?i)\\b(find|search|lookup|look\\s+up|retrieve|check|show)\\b", " ")
            .replaceAll("(?i)\\b(key\\s*words?|keyword)\\b\\s*[:：]?", " ")
            .replaceAll("(?i)\\b(any|related|relevant)\\s+(documents?|docs?|files?|materials?|records?)\\b", " ")
            .replaceAll("(?i)\\b(documents?|docs?|files?|materials?|records?|knowledge\\s*base|kb)\\b", " ")
            .replaceAll("(?i)\\b(about|for|on|regarding|related\\s+to)\\b", " ");
        focused = focused
            .replace("内部知识库中", " ")
            .replace("内部知识库", " ")
            .replace("知识库中", " ")
            .replace("知识库", " ")
            .replace("以关键词", " ")
            .replace("以关键字", " ")
            .replace("关键词", " ")
            .replace("关键字", " ")
            .replace("进行检索", " ")
            .replace("进行搜索", " ")
            .replace("进行查找", " ")
            .replace("我们有没有", " ")
            .replace("你们有没有", " ")
            .replace("有没有关于", " ")
            .replace("是否有关于", " ")
            .replace("是否存在关于", " ")
            .replace("有无关于", " ")
            .replace("有没有", " ")
            .replace("是否有", " ")
            .replace("是否存在", " ")
            .replace("有无", " ")
            .replace("请帮我查找", " ")
            .replace("请帮我查询", " ")
            .replace("请帮我检索", " ")
            .replace("请帮我搜索", " ")
            .replace("帮我查找", " ")
            .replace("帮我查询", " ")
            .replace("帮我检索", " ")
            .replace("帮我搜索", " ")
            .replace("帮我查", " ")
            .replace("请查询", " ")
            .replace("请检索", " ")
            .replace("请搜索", " ")
            .replace("查一下", " ")
            .replace("查询一下", " ")
            .replace("检索一下", " ")
            .replace("搜索一下", " ")
            .replace("检索", " ")
            .replace("搜索", " ")
            .replace("查找", " ");
        focused = focused
            .replaceAll("的(相关)?(文档|资料|文件|材料)", " ")
            .replaceAll("(相关)?(文档|资料|文件|材料)$", " ")
            .replaceAll("(知识库|资料库)$", " ")
            .replaceAll("[?？!！。。，,;；:：]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return focused.length() >= 2 ? focused : value;
    }

    private boolean looksLikeRetrievalQuestion(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("有没有")
            || normalized.contains("是否有")
            || normalized.contains("是否存在")
            || normalized.contains("有无")
            || normalized.contains("查一下")
            || normalized.contains("查询一下")
            || normalized.contains("检索一下")
            || normalized.contains("搜索一下")
            || normalized.contains("请查询")
            || normalized.contains("请检索")
            || normalized.contains("请搜索")
            || normalized.contains("关键词")
            || normalized.contains("关键字")
            || normalized.contains("知识库")
            || normalized.contains("文档")
            || normalized.contains("资料")
            || normalized.contains("文件")
            || normalized.matches(".*\\b(do|does|did)\\s+(we|you)\\s+(have|keep|own)\\b.*")
            || normalized.matches(".*\\b(is|are)\\s+there\\b.*")
            || normalized.matches(".*\\b(find|search|lookup|retrieve|check)\\b.*")
            || normalized.matches(".*\\bkey\\s*words?\\b.*")
            || normalized.matches(".*\\bkeyword\\b.*")
            || normalized.matches(".*\\b(documents?|docs?|files?|materials?|records?|knowledge\\s*base|kb)\\b.*");
    }

    private boolean intentMatches(String ruleIntent, String intentName) {
        if (ruleIntent == null || ruleIntent.isBlank()) {
            return true;
        }
        return normalize(ruleIntent).equals(normalize(intentName));
    }

    private boolean sourceMatches(String sourceWord, String query, List<String> tokens) {
        String source = normalize(sourceWord);
        if (source.isBlank()) {
            return true;
        }
        if (!query.isBlank() && query.contains(source)) {
            return true;
        }
        String compactSource = compactTechnicalTerm(source);
        String compactQuery = compactTechnicalTerm(query);
        if (compactSource.length() >= 4 && compactQuery.length() >= 4
            && (compactQuery.contains(compactSource) || compactSource.contains(compactQuery))) {
            return true;
        }
        for (String token : tokens) {
            String normalizedToken = normalize(token);
            if (normalizedToken.equals(source) || normalizedToken.contains(source) || source.contains(normalizedToken)) {
                return true;
            }
            String compactToken = compactTechnicalTerm(normalizedToken);
            if (compactSource.length() >= 4 && compactToken.length() >= 4
                && (compactToken.equals(compactSource)
                || compactToken.contains(compactSource)
                || compactSource.contains(compactToken))) {
                return true;
            }
        }
        return false;
    }

    private void applySemanticLexicon(Set<String> expanded, String query, List<String> tokens) {
        for (RetrievalRuleService.SemanticLexiconEntry entry : ruleService.snapshot().semanticLexicon()) {
            if (!lexiconMatches(entry, query, tokens)) {
                continue;
            }
            for (int i = 0; i < Math.max(1, entry.weight()); i++) {
                addToken(expanded, entry.term());
                addToken(expanded, entry.mappedTerm());
                for (String alias : entry.aliases()) {
                    addToken(expanded, alias);
                }
                addToken(expanded, entry.category());
                addToken(expanded, entry.domain());
            }
        }
    }

    private void applyBilingualRetrieval(Set<String> expanded, String query, List<String> tokens) {
        for (BilingualRetrievalEntry entry : BILINGUAL_RETRIEVAL_ENTRIES) {
            if (!bilingualEntryMatches(entry, query, tokens)) {
                continue;
            }
            for (String value : entry.expansions()) {
                addToken(expanded, value);
            }
        }
    }

    private boolean bilingualEntryMatches(BilingualRetrievalEntry entry, String query, List<String> tokens) {
        if (entry == null) {
            return false;
        }
        for (String trigger : entry.triggers()) {
            if (sourceMatches(trigger, query, tokens)) {
                return true;
            }
        }
        return false;
    }

    private boolean lexiconMatches(RetrievalRuleService.SemanticLexiconEntry entry, String query, List<String> tokens) {
        if (entry == null) {
            return false;
        }
        if (sourceMatches(entry.term(), query, tokens) || sourceMatches(entry.mappedTerm(), query, tokens)) {
            return true;
        }
        for (String alias : entry.aliases()) {
            if (sourceMatches(alias, query, tokens)) {
                return true;
            }
        }
        return false;
    }

    private void addRawLexiconValue(Set<String> target, String value, String normalizedQuery) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalizedQuery.contains(normalized)) {
            return;
        }
        target.add(normalized);
    }

    private void addToken(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : tokenizer.searchTokens(value)) {
            if (!token.isBlank()) {
                target.add(token);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String compactTechnicalTerm(String value) {
        return value == null ? "" : value
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s_\\-./]+", "");
    }

    private static BilingualRetrievalEntry entry(List<String> triggers, List<String> expansions) {
        return new BilingualRetrievalEntry(triggers, expansions);
    }

    private record BilingualRetrievalEntry(List<String> triggers, List<String> expansions) {
        private BilingualRetrievalEntry {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
            expansions = expansions == null ? List.of() : List.copyOf(expansions);
        }
    }
}
