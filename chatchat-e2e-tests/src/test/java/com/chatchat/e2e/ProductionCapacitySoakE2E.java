package com.chatchat.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "chatchat.e2e.capacity-soak.live", matches = "true")
class ProductionCapacitySoakE2E {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    @Test
    void deployedAgentRuntimeSustainsConcurrentEvidenceBoundInference() throws Exception {
        String endpoint = required("chatchat.e2e.api-base-url") + "/api/v1/interactions/chat";
        int durationSeconds = integer("chatchat.e2e.soak-duration-seconds", 300, 1, 259_200);
        int concurrency = integer("chatchat.e2e.soak-concurrency", 8, 1, 256);
        double minimumSuccessRate = decimal("chatchat.e2e.soak-min-success-rate", 0.999d);
        long maximumP95Ms = integer("chatchat.e2e.soak-max-p95-ms", 180_000, 1, 600_000);
        String query = System.getProperty("chatchat.e2e.inference-query",
            "Use current public evidence to summarize the latest AI industry development with sources.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
        List<Sample> samples = new ArrayList<>();

        do {
            List<CompletableFuture<Sample>> batch = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                String requestId = "capacity-soak-" + System.nanoTime() + "-" + index;
                batch.add(invoke(endpoint, requestId, query));
            }
            for (CompletableFuture<Sample> future : batch) {
                samples.add(future.get(4, TimeUnit.MINUTES));
            }
        } while (System.nanoTime() < deadline);

        long successes = samples.stream().filter(Sample::success).count();
        double successRate = samples.isEmpty() ? 0.0d : (double) successes / samples.size();
        List<Long> latencies = samples.stream().map(Sample::latencyMs).sorted(Comparator.naturalOrder()).toList();
        long p95 = latencies.isEmpty() ? Long.MAX_VALUE
            : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95d) - 1));

        assertThat(samples).hasSizeGreaterThanOrEqualTo(concurrency);
        assertThat(successRate)
            .as("soak success rate; failures=%s", samples.stream().filter(sample -> !sample.success()).limit(10).toList())
            .isGreaterThanOrEqualTo(minimumSuccessRate);
        assertThat(p95).as("deployed Agent inference P95 latency").isLessThanOrEqualTo(maximumP95Ms);
    }

    private CompletableFuture<Sample> invoke(String endpoint, String requestId, String query) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "requestId", requestId,
            "conversationId", requestId,
            "userId", "capacity-soak-user",
            "query", query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        String authHeader = optional("chatchat.e2e.api-auth-header");
        String authValue = firstText(System.getProperty("chatchat.e2e.api-auth-value"),
            System.getenv("CHATCHAT_E2E_API_AUTH_VALUE"));
        if (authHeader != null && authValue != null) {
            builder.header(authHeader, authValue);
        }
        long started = System.nanoTime();
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
            .handle((response, error) -> {
                long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (error != null) {
                    return new Sample(false, latency, -1, error.getClass().getSimpleName());
                }
                boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && validAgentResponse(response.body());
                return new Sample(success, latency, response.statusCode(),
                    success ? "ok" : preview(response.body()));
            });
    }

    private boolean validAgentResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                return false;
            }
            JsonNode data = root.path("data");
            String answer = firstText(data.path("answer").asText(null),
                firstText(data.path("content").asText(null), root.path("answer").asText(null)));
            return answer != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int integer(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(name, String.valueOf(fallback)));
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private double decimal(String name, double fallback) {
        double value = Double.parseDouble(System.getProperty(name, String.valueOf(fallback)));
        if (value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
        return value;
    }

    private String required(String name) {
        String value = optional(name);
        if (value == null) throw new IllegalStateException("Missing required system property: " + name);
        return value.replaceAll("/+$", "");
    }

    private String optional(String name) {
        return firstText(System.getProperty(name), null);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? (second == null || second.isBlank() ? null : second.trim()) : first.trim();
    }

    private String preview(String value) {
        if (value == null) return "empty response";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private record Sample(boolean success, long latencyMs, int statusCode, String detail) {
    }
}
