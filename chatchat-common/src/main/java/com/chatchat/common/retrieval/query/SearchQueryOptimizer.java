package com.chatchat.common.retrieval.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Execution-only optimization. The caller retains ownership of tools, scope and business intent. */
public final class SearchQueryOptimizer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SearchQueryOptimizer.class);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final QueryOptimizationBudget budget;
    private final QueryRefiner refiner;
    private final List<QueryTermProtector> protectors;
    private final LongSupplier clock;
    private final ExecutorService executor;
    private final Semaphore querySlots;
    public enum Mode { TEXT, DSL }
    public enum Status { SUCCEEDED, PARTIAL, FAILED }
    @FunctionalInterface public interface Transport {
        Map<String,Object> execute(Map<String,Object> body, int timeoutMillis) throws Exception;
    }
    @FunctionalInterface public interface MultiTransport {
        Map<String,Object> execute(List<Map<String,Object>> bodies, int timeoutMillis, int concurrency) throws Exception;
    }
    public record Prepared(Map<String,Object> body, List<Map<String,Object>> transformations) { }
    public record BatchReceipt(String batchId, String status, String errorCode, long elapsedMillis) { }
    public record BatchResult(Status status, List<Map<String,Object>> hits, List<BatchReceipt> batches,
                              List<Map<String,Object>> transformations) { }

    public SearchQueryOptimizer() { this(new QueryOptimizationBudget(), null); }
    public SearchQueryOptimizer(QueryOptimizationBudget budget, QueryRefiner refiner) { this(budget, refiner, List.of(), System::nanoTime); }
    public SearchQueryOptimizer(QueryOptimizationBudget budget, QueryRefiner refiner, List<QueryTermProtector> protectors) {
        this(budget, refiner, protectors, System::nanoTime);
    }
    public SearchQueryOptimizer(QueryOptimizationBudget budget, QueryRefiner refiner, LongSupplier clock) {
        this(budget, refiner, List.of(), clock);
    }
    private SearchQueryOptimizer(QueryOptimizationBudget budget, QueryRefiner refiner, List<QueryTermProtector> protectors, LongSupplier clock) {
        this.budget = Objects.requireNonNull(budget); this.refiner = refiner; this.clock = clock;
        this.protectors = List.copyOf(protectors);
        querySlots = new Semaphore(budget.getMaxConcurrent(), true);
        executor = new ThreadPoolExecutor(budget.getMaxConcurrent(), budget.getMaxConcurrent(), 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(budget.getMaxBatches()), task -> {
                var thread = new Thread(task, "opensearch-query-budget"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
    public QueryOptimizationBudget budget() { return budget; }
    public Map<String,Object> withAudit(java.util.function.Supplier<Map<String,Object>> operation) {
        try (var scope = openScope()) {
            var result = new LinkedHashMap<>(operation.get());
            result.put("queryOptimization", scope.audit());
            return result;
        }
    }
    public void checkIndependentCount(int count) {
        if (count < 0 || count > budget.getMaxBatches())
            throw new QueryOptimizationException("QUERY_BATCH_BUDGET_EXCEEDED",
                "Submit fewer independent query units; the execution limit is unchanged",
                Map.of("requestedBatches", count, "maximumBatches", budget.getMaxBatches()));
    }
    public <T> T withinBudget(Callable<T> operation) {
        try (var scope = openScope()) { return await(operation, scope, Math.min(scope.remainingMillis(), budget.getRequestTimeoutMs())); }
    }

    public Scope openScope() {
        Scope parent = CURRENT.get();
        Scope scope = parent == null ? new Scope(clock.getAsLong(), null) : new Scope(parent.started, parent);
        CURRENT.set(scope); return scope;
    }
    public final class Scope implements AutoCloseable {
        private final long started;
        private final Scope parent;
        private int requests, candidates, refinements;
        private final Map<String,ProtectedQueryKeywords.Extraction> extractions = new LinkedHashMap<>();
        private final List<Map<String,Object>> transformations = new ArrayList<>();
        private boolean closed;
        private Scope(long started, Scope parent) { this.started = started; this.parent = parent; }
        private Scope root() { return parent == null ? this : parent.root(); }
        /** Attach to an existing tool result/evidence envelope; original input stays with its caller. */
        public Map<String,Object> audit() {
            return Map.of("schemaVersion", "query-optimization.v1", "requestCount", root().requests,
                "refinementCalls", root().refinements, "transformations", List.copyOf(root().transformations),
                "coverage", "BOUNDED_TOP_K", "budgets", Map.of("maxBatches", budget.getMaxBatches(),
                    "maxCandidates", budget.getMaxCandidates(), "maxTotalCandidates", budget.getMaxTotalCandidates(),
                    "totalTimeoutMs", budget.getTotalTimeoutMs(), "maxEstimatedClauses", budget.getMaxEstimatedClauses()));
        }
        public int remainingMillis() {
            long remaining = budget.getTotalTimeoutMs() - TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started);
            if (Thread.currentThread().isInterrupted()) throw new QueryOptimizationException("QUERY_CANCELLED", "Search interrupted");
            if (remaining <= 0) throw new QueryOptimizationException("QUERY_TOTAL_TIMEOUT", "Overall query deadline exhausted");
            return (int) Math.min(Integer.MAX_VALUE, remaining);
        }
        private synchronized void reserve(int count, int rows) {
            var root = root();
            if (root != this) { root.reserve(count, rows); return; }
            remainingMillis();
            if ((long) requests + count > budget.getMaxBatches()) throw new QueryOptimizationException("QUERY_BATCH_BUDGET_EXCEEDED", "Too many requests in one retrieval");
            if ((long) candidates + rows > budget.getMaxTotalCandidates()) throw new QueryOptimizationException("QUERY_CANDIDATE_BUDGET_EXCEEDED", "Total candidate budget exhausted");
            requests += count; candidates += rows;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }

    public Prepared prepare(Map<String,Object> body, Mode mode) {
        try (var scope = openScope()) {
            var transforms = new ArrayList<Map<String,Object>>();
            @SuppressWarnings("unchecked") var copy = (Map<String,Object>) copy(body, 0, new int[]{0});
            if (mode == Mode.TEXT) rewrite(copy.get("query"), false, transforms, scope);
            validate(copy);
            return new Prepared(copy, List.copyOf(transforms));
        }
    }
    public Map<String,Object> search(Map<String,Object> body, Mode mode, Transport transport) {
        try (var scope = openScope()) {
            var prepared = prepare(body, mode);
            scope.reserve(1, size(prepared.body()));
            int timeout = Math.min(Math.min(scope.remainingMillis(), budget.getRequestTimeoutMs()), timeoutMillis(prepared.body().get("timeout")));
            var response = await(() -> transport.execute(withTimeout(prepared.body(), timeout), timeout), scope, timeout);
            checkResponse(response);
            var result = new LinkedHashMap<>(response);
            result.put("_queryOptimization", Map.of("status", "SUCCEEDED", "coverage", "BOUNDED_TOP_K",
                "transformations", prepared.transformations(), "requestCount", scope.root().requests,
                "refinementCalls", scope.root().refinements, "queryFingerprint", hash(prepared.body().toString())));
            log.info("query_optimization status=SUCCEEDED queryFingerprint={} requests={} transformations={} remainingMs={}",
                hash(prepared.body().toString()), scope.root().requests, prepared.transformations().size(), scope.remainingMillis());
            return result;
        } catch (QueryOptimizationException error) {
            log.warn("query_optimization status=FAILED code={}", error.code()); throw error;
        }
    }

    /** Batches are ALREADY independent/OR branches. No flattening or splitting of arbitrary bool DSL. */
    public BatchResult searchIndependent(List<Map<String,Object>> bodies, Mode mode, MultiTransport transport) {
        if (bodies == null || bodies.isEmpty()) return new BatchResult(Status.SUCCEEDED, List.of(), List.of(), List.of());
        try (var scope = openScope()) {
            if (bodies.size() > budget.getMaxBatches()) throw new QueryOptimizationException("QUERY_BATCH_BUDGET_EXCEEDED", "Independent intent count exceeds budget");
            var prepared = new ArrayList<Map<String,Object>>(); var transformations = new ArrayList<Map<String,Object>>();
            int rows = 0;
            for (var body : bodies) { var item = prepare(body, mode); prepared.add(item.body()); transformations.addAll(item.transformations()); rows = Math.addExact(rows, size(item.body())); }
            scope.reserve(prepared.size(), rows);
            int timeout = Math.min(scope.remainingMillis(), budget.getRequestTimeoutMs());
            long start = clock.getAsLong();
            var root = await(() -> transport.execute(prepared.stream().map(b -> withTimeout(b, timeout)).toList(), timeout,
                budget.getMaxConcurrent()), scope, timeout, budget.getMaxConcurrent());
            if (!(root.get("responses") instanceof List<?> responses) || responses.size() != bodies.size())
                throw new QueryOptimizationException("QUERY_INVALID_BATCH_RESPONSE", "Response count differs from submitted batch count");
            var receipts = new ArrayList<BatchReceipt>(); var hits = new LinkedHashMap<String,Map<String,Object>>();
            int failed = 0;
            for (int i = 0; i < responses.size(); i++) {
                String batchId = "q" + (i + 1);
                try {
                    if (!(responses.get(i) instanceof Map<?,?> response)) throw new QueryOptimizationException("QUERY_INVALID_RESPONSE", "Batch response must be an object");
                    @SuppressWarnings("unchecked") var typed = (Map<String,Object>) response;
                    checkResponse(typed);
                    merge(hits, typed, batchId);
                    receipts.add(new BatchReceipt(batchId, "SUCCEEDED", "", TimeUnit.NANOSECONDS.toMillis(clock.getAsLong()-start)));
                } catch (QueryOptimizationException failure) {
                    failed++; receipts.add(new BatchReceipt(batchId, "FAILED", failure.code(), TimeUnit.NANOSECONDS.toMillis(clock.getAsLong()-start)));
                }
            }
            var ordered = hits.values().stream().sorted(Comparator.<Map<String,Object>>comparingDouble(h -> ((Number)h.get("fusionScore")).doubleValue()).reversed()
                .thenComparing(h -> String.valueOf(h.get("_index")) + ":" + h.get("_id"))).toList();
            Status status = failed == 0 ? Status.SUCCEEDED : failed == bodies.size() ? Status.FAILED : Status.PARTIAL;
            for (int i=0; i<receipts.size(); i++) log.info("query_batch batchId={} queryFingerprint={} status={} code={} elapsedMs={}",
                receipts.get(i).batchId(), hash(prepared.get(i).toString()), receipts.get(i).status(), receipts.get(i).errorCode(), receipts.get(i).elapsedMillis());
            log.info("query_optimization status={} batches={} failed={} hits={}", status, bodies.size(), failed, ordered.size());
            return new BatchResult(status, ordered, List.copyOf(receipts), List.copyOf(transformations));
        }
    }

    public String text(String original, int fields) {
        if (original == null || original.codePointCount(0, original.length()) <= budget.getLongTextThreshold()) return original;
        try (var scope = openScope()) { return extract(original, Math.max(1, fields), scope).text(); }
    }
    /** Reuse the accepted lexical extraction before existing embedding input limits are applied. */
    public String semanticText(String original) {
        if (original == null || original.codePointCount(0, original.length()) <= budget.getLongTextThreshold()) return original;
        try (var scope = openScope()) {
            String prefix = hash(original) + ":";
            for (var entry : scope.root().extractions.entrySet()) if (entry.getKey().startsWith(prefix)) return entry.getValue().text();
            return extract(original, 1, scope).text();
        }
    }
    private ProtectedQueryKeywords.Extraction extract(String original, int fields, Scope scope) {
        if (original.getBytes(StandardCharsets.UTF_8).length > budget.getMaxInputBytes())
            throw new QueryOptimizationException("QUERY_INPUT_BUDGET_EXCEEDED", "Input is too large; supply independent retrieval intents");
        String key = hash(original) + ":" + fields;
        if (scope.root().extractions.containsKey(key)) return scope.root().extractions.get(key);
        int tokenBudget = Math.max(1, (budget.getMaxEstimatedClauses() - budget.getClauseHeadroom()) / fields);
        var vocabulary = new ArrayList<String>();
        var candidates = new ArrayList<String>();
        for (var protector : protectors) {
            var phrases = await(() -> protector.protectedPhrases(original, budget.getMaxKeywords()), scope,
                Math.min(scope.remainingMillis(), budget.getRequestTimeoutMs()));
            if (phrases == null || phrases.stream().anyMatch(phrase -> phrase == null || phrase.isBlank() || !original.contains(phrase)))
                throw new QueryOptimizationException("QUERY_VOCABULARY_INVALID", "Vocabulary protection returned a non-source phrase");
            vocabulary.addAll(phrases);
            candidates.addAll(await(() -> protector.keywordCandidates(original, budget.getMaxKeywords()), scope,
                Math.min(scope.remainingMillis(), budget.getRequestTimeoutMs())));
        }
        var extracted = ProtectedQueryKeywords.extract(original, budget, tokenBudget, candidates, vocabulary);
        List<String> refined = List.of();
        if (budget.isModelRefinementEnabled() && scope.root().refinements < budget.getMaxRefinementCalls()) {
            if (refiner == null) throw new QueryOptimizationException("QUERY_REFINER_UNAVAILABLE", "Model refinement enabled without a registered refiner");
            scope.root().refinements++;
            int timeout = Math.min(scope.remainingMillis(), budget.getRefinementTimeoutMs());
            try {
                var protectedPhrases = extracted.protectedPhrases();
                refined = await(() -> refiner.refine(original, protectedPhrases,
                    budget.getMaxKeywords(), timeout), scope, timeout);
                log.info("query_refinement status=SUCCEEDED queryHash={} keywords={}", hash(original), refined == null ? 0 : refined.size());
            } catch (QueryOptimizationException failure) {
                if ("QUERY_CANCELLED".equals(failure.code()) || "QUERY_TOTAL_TIMEOUT".equals(failure.code())) throw failure;
                log.info("query_refinement status=DEGRADED code={} fallback=deterministic", failure.code());
            }
        }
        scope.remainingMillis();
        if (refined != null && !refined.isEmpty()) {
            var preferred = new ArrayList<>(refined); preferred.addAll(candidates);
            extracted = ProtectedQueryKeywords.extract(original, budget, tokenBudget, preferred, vocabulary);
        }
        scope.root().extractions.put(key, extracted);
        var audit = Map.<String,Object>of("originalQuerySha256", hash(original), "optimizedQuery", extracted.text(),
            "protectedPhrases", extracted.protectedPhrases(), "keywords", extracted.keywords(),
            "semanticGuarantee", "LEXICAL_APPROXIMATION");
        scope.root().transformations.add(audit);
        log.debug("query_transformation originalQuerySha256={} optimizedQuery={} protectedPhrases={}",
            hash(original), extracted.text(), extracted.protectedPhrases());
        return extracted;
    }

    @SuppressWarnings("unchecked")
    private void rewrite(Object value, boolean fixed, List<Map<String,Object>> transformations, Scope scope) {
        if (value instanceof Map<?,?> raw) {
            var map = (Map<String,Object>) raw;
            for (String key : List.of("multi_match", "match", "match_phrase")) {
                if (fixed || !(map.get(key) instanceof Map<?,?>)) continue;
                var clause = (Map<String,Object>) map.get(key);
                if (!"multi_match".equals(key)) for (String field : new ArrayList<>(clause.keySet()))
                    if (clause.get(field) instanceof String text && text.codePointCount(0, text.length()) > budget.getLongTextThreshold())
                        clause.put(field, new LinkedHashMap<>(Map.of("query", text)));
                var targets = "multi_match".equals(key) ? List.of(clause) : clause.values().stream().filter(Map.class::isInstance).map(v -> (Map<String,Object>)v).toList();
                for (var target : targets) if (target.get("query") instanceof String original && original.codePointCount(0, original.length()) > budget.getLongTextThreshold()) {
                    if ("match_phrase".equals(key)) throw new QueryOptimizationException("QUERY_PHRASE_BUDGET_EXCEEDED", "A long exact phrase cannot be compressed safely");
                    if ("and".equalsIgnoreCase(String.valueOf(target.get("operator"))) || target.containsKey("minimum_should_match"))
                        throw new QueryOptimizationException("QUERY_REQUIRES_STRUCTURED_CONSTRAINTS", "Conjunctive text must not be compressed");
                    int fields = target.get("fields") instanceof List<?> list ? list.size() : 1;
                    var extraction = extract(original, fields, scope);
                    target.put("query", extraction.text()); target.put("analyzer", "standard");
                    transformations.add(Map.of("originalQuery", original, "originalQuerySha256", hash(original),
                        "optimizedQuery", extraction.text(), "protectedPhrases", extraction.protectedPhrases(),
                        "keywords", extraction.keywords(), "semanticGuarantee", "LEXICAL_APPROXIMATION"));
                }
            }
            for (var entry : map.entrySet()) rewrite(entry.getValue(), fixed || Set.of("filter", "must_not").contains(entry.getKey()), transformations, scope);
        } else if (value instanceof List<?> list) for (Object item : list) rewrite(item, fixed, transformations, scope);
    }

    public void validate(Map<String,Object> body) {
        copy(body, 0, new int[]{0});
        if (size(body) > budget.getMaxCandidates()) throw new QueryOptimizationException("QUERY_CANDIDATE_BUDGET_EXCEEDED", "Single query candidate limit exceeded");
        if (body.get("from") instanceof Number offset && (offset.longValue() < 0 || offset.longValue() > budget.getMaxOffset()))
            throw new QueryOptimizationException("QUERY_OFFSET_BUDGET_EXCEEDED", "Deep pagination exceeds budget");
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > budget.getMaxRequestBytes())
            throw new QueryOptimizationException("QUERY_REQUEST_BUDGET_EXCEEDED", "Request exceeds byte budget");
        long clauses = complexity(body);
        if (clauses > budget.getMaxEstimatedClauses()) throw new QueryOptimizationException("QUERY_DSL_BUDGET_EXCEEDED", "Estimated analyzer/field expansion exceeds budget", Map.of("estimatedClauses", clauses));
    }
    private long complexity(Object value) {
        long cost = 0;
        if (value instanceof Map<?,?> map) {
            if (map.get("fields") instanceof List<?> fields && fields.size() > budget.getMaxFields())
                throw new QueryOptimizationException("QUERY_FIELDS_BUDGET_EXCEEDED", "Field expansion exceeds budget");
            if (map.get("k") instanceof Number k && k.longValue() > budget.getMaxCandidates())
                throw new QueryOptimizationException("QUERY_CANDIDATE_BUDGET_EXCEEDED", "Vector candidate count exceeds budget");
            for (String aggregation : List.of("terms", "histogram", "date_histogram", "composite"))
                if (map.get(aggregation) instanceof Map<?,?> spec && spec.get("size") instanceof Number size
                    && size.longValue() > budget.getMaxAggregationBuckets())
                    throw new QueryOptimizationException("QUERY_AGGREGATION_BUDGET_EXCEEDED", "Aggregation bucket count exceeds budget");
            if (map.containsKey("query_string") || map.containsKey("simple_query_string") || map.containsKey("script") || map.containsKey("script_score")
                || map.containsKey("regexp") || map.containsKey("wildcard"))
                throw new QueryOptimizationException("QUERY_UNBOUNDED_DSL", "This query requires an explicitly reviewed bounded query capability");
            if (map.get("fields") instanceof List<?> fields && fields.stream().anyMatch(v -> String.valueOf(v).contains("*")))
                throw new QueryOptimizationException("QUERY_UNBOUNDED_FIELDS", "Wildcard field expansion is not budgetable");
            if (map.get("terms") instanceof Map<?,?> terms) for (Object item : terms.values()) {
                if (item instanceof List<?> list && list.size() > budget.getMaxTermsValues())
                    throw new QueryOptimizationException("QUERY_TERMS_BUDGET_EXCEEDED", "A terms/authorization list exceeds budget; it was not truncated");
                if (item instanceof Map<?,?>) throw new QueryOptimizationException("QUERY_UNBOUNDED_TERMS", "Terms lookup cannot be estimated locally");
            }
            if (map.get("query") instanceof String text) {
                int fields = map.get("fields") instanceof List<?> list ? Math.max(1, list.size()) : 1;
                if (!"standard".equals(map.get("analyzer")) && map.get("fields") instanceof List<?> names)
                    fields = names.stream().mapToInt(name -> String.valueOf(name).contains(".pinyin") || String.valueOf(name).contains(".ngram")
                        ? budget.getAnalyzerExpansionFactor() : 1).sum();
                cost += (long) ProtectedQueryKeywords.tokens(text) * fields;
            }
            for (String match : List.of("match", "match_phrase")) if (map.get(match) instanceof Map<?,?> fields)
                for (Object field : fields.values()) if (field instanceof String text) cost += ProtectedQueryKeywords.tokens(text);
            for (var entry : map.entrySet()) if (!Set.of("_source", "vector", "filter").contains(String.valueOf(entry.getKey()))) cost += complexity(entry.getValue());
            // Filters are validated too; terms values do not each become a bool clause.
            if (map.containsKey("filter")) cost += complexity(map.get("filter"));
            if (map.containsKey("term") || map.containsKey("range") || map.containsKey("exists")) cost++;
        } else if (value instanceof List<?> list) for (Object item : list) cost += complexity(item);
        return cost;
    }
    private Object copy(Object value, int depth, int[] nodes) {
        if (depth > budget.getMaxDslDepth() || ++nodes[0] > budget.getMaxDslNodes()) throw new QueryOptimizationException("QUERY_DSL_BUDGET_EXCEEDED", "DSL depth/node budget exceeded");
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            for (var entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), copy(entry.getValue(), depth+1, nodes));
            return result;
        }
        if (value instanceof Collection<?> list) { var result = new ArrayList<>(); for (Object item : list) result.add(copy(item, depth+1, nodes)); return result; }
        if (value instanceof String text && text.getBytes(StandardCharsets.UTF_8).length > budget.getMaxInputBytes())
            throw new QueryOptimizationException("QUERY_INPUT_BUDGET_EXCEEDED", "Query value exceeds input budget");
        return value;
    }
    private int size(Map<String,Object> body) {
        Object value = body.getOrDefault("size", 10);
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0)
            throw new QueryOptimizationException("QUERY_INVALID_SIZE", "size must be a nonnegative integer");
        return ((Number)value).intValue();
    }
    private Map<String,Object> withTimeout(Map<String,Object> body, int millis) {
        var result = new LinkedHashMap<>(body);
        // A pre-existing timeout may be stricter; never extend it.
        int existing = timeoutMillis(body.get("timeout"));
        result.put("timeout", Math.min(millis, existing) + "ms"); return result;
    }
    private static int timeoutMillis(Object raw) {
        if (raw == null) return Integer.MAX_VALUE;
        String text = String.valueOf(raw);
        try {
            double multiplier = text.endsWith("ms") ? 1 : text.endsWith("s") ? 1000 : text.endsWith("m") ? 60000 : 1;
            String number = text.replaceFirst("(?:ms|s|m)$", "");
            return (int)Math.min(Integer.MAX_VALUE, Math.max(1, Double.parseDouble(number)*multiplier));
        } catch (NumberFormatException invalid) { throw new QueryOptimizationException("QUERY_INVALID_TIMEOUT", "Unsupported timeout"); }
    }
    private <T> T await(Callable<T> operation, Scope scope, int timeout) {
        return await(operation, scope, timeout, 1);
    }
    private <T> T await(Callable<T> operation, Scope scope, int timeout, int permits) {
        Future<T> future;
        var context = org.slf4j.MDC.getCopyOfContextMap();
        try { future = executor.submit(() -> {
            if (context != null) org.slf4j.MDC.setContextMap(context);
            boolean acquired = false;
            try {
                acquired = querySlots.tryAcquire(permits, Math.min(timeout, scope.remainingMillis()), TimeUnit.MILLISECONDS);
                if (!acquired) throw new QueryOptimizationException("QUERY_TIMEOUT", "Query concurrency queue deadline exceeded");
                return operation.call();
            } finally { if (acquired) querySlots.release(permits); org.slf4j.MDC.clear(); }
        }); }
        catch (RejectedExecutionException full) { throw new QueryOptimizationException("QUERY_BUSY", "Bounded search executor is full"); }
        try { return future.get(Math.min(timeout, scope.remainingMillis()), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { future.cancel(true); Thread.currentThread().interrupt(); throw new QueryOptimizationException("QUERY_CANCELLED", "Search cancelled"); }
        catch (TimeoutException expired) { future.cancel(true); throw new QueryOptimizationException("QUERY_TIMEOUT", "Search/refinement deadline exceeded"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof QueryOptimizationException diagnosed) throw diagnosed;
            for (Throwable cause = failed.getCause(); cause != null; cause = cause.getCause()) {
                String message = cause.getMessage();
                if (message != null && (message.contains("maxClauseCount") || message.contains("too_many_clauses")))
                    throw new QueryOptimizationException("QUERY_DSL_BUDGET_EXCEEDED", "Backend analyzer expansion exceeded the configured estimate; refine keywords or tune the analyzer profile");
            }
            throw new QueryOptimizationException("QUERY_EXECUTION_FAILED", "Search/refinement execution failed", Map.of("errorType", failed.getCause().getClass().getSimpleName()));
        }
    }
    private static void checkResponse(Map<String,Object> response) {
        if (response == null || response.containsKey("error")) throw new QueryOptimizationException("QUERY_EXECUTION_FAILED", "Search returned an error");
        if (Boolean.TRUE.equals(response.get("timed_out"))) throw new QueryOptimizationException("QUERY_TIMEOUT", "OpenSearch timed out");
        if (response.get("_shards") instanceof Map<?,?> shards && shards.get("failed") instanceof Number n && n.intValue() > 0)
            throw new QueryOptimizationException("QUERY_PARTIAL_SHARDS", "Some search shards failed");
        if (!(response.get("hits") instanceof Map<?,?> hits) || !(hits.get("hits") instanceof List<?>))
            throw new QueryOptimizationException("QUERY_INVALID_RESPONSE", "Search hits missing");
    }
    @SuppressWarnings("unchecked")
    private void merge(Map<String,Map<String,Object>> merged, Map<String,Object> response, String batch) {
        var rows = (List<?>) ((Map<?,?>)response.get("hits")).get("hits");
        for (Object row : rows) if (!(row instanceof Map<?,?> hit) || hit.get("_id") == null || hit.get("_index") == null)
            throw new QueryOptimizationException("QUERY_INVALID_RESPONSE", "Batch hit requires index and document identity");
        for (int i=0; i<rows.size(); i++) {
            if (!(rows.get(i) instanceof Map<?,?> hit) || hit.get("_id") == null || hit.get("_index") == null)
                throw new QueryOptimizationException("QUERY_INVALID_RESPONSE", "Batch hit requires index and document identity");
            String key = hit.get("_index") + "\u0000" + hit.get("_id");
            var current = merged.computeIfAbsent(key, ignored -> { var copy = new LinkedHashMap<String,Object>((Map<String,Object>)hit); copy.put("fusionScore", 0D); copy.put("batchEvidence", new ArrayList<Map<String,Object>>()); return copy; });
            double contribution = 1D / (budget.getRrfRankConstant() + i + 1);
            current.put("fusionScore", ((Number)current.get("fusionScore")).doubleValue()+contribution);
            ((List<Map<String,Object>>)current.get("batchEvidence")).add(Map.of("batchId", batch, "rank", i+1, "rawScore", hit.get("_score") instanceof Number score ? score : 0D, "contribution", contribution));
        }
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public void close() { executor.shutdownNow(); }
}
