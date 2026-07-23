package com.chatchat.runtime.news.compliance;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.http.ProxyAwareHttpClientFactory;
import com.chatchat.runtime.news.model.NewsSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** RFC 9309-oriented, fail-closed robots.txt preflight for news collection. */
@Service
public class RobotsTxtComplianceService {
    public static final String BLOCKED_PREFIX = "机器人协议检测未通过：";
    private final NewsRuntimeProperties.Robots properties;
    private final HttpClient client;
    private final Map<String, CachedPolicy> cache = new ConcurrentHashMap<>();

    @Autowired
    public RobotsTxtComplianceService(NewsRuntimeProperties runtimeProperties) {
        this(runtimeProperties.getRobots(), ProxyAwareHttpClientFactory.builder()
            .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    RobotsTxtComplianceService(NewsRuntimeProperties.Robots properties, HttpClient client) {
        this.properties = properties;
        this.client = client;
    }

    public RobotsComplianceReport check(NewsSource source) {
        Set<String> targets = new LinkedHashSet<>();
        addUrl(targets, source.entryUrl());
        collectConfiguredUrls(source.configuration(), targets);
        if (!properties.isEnabled()) {
            return report(true, "DISABLED", source.entryUrl(), robotsUrl(source.entryUrl()), null, null,
                "robots.txt 检测已由运行时配置关闭。", targets.size());
        }
        RobotsComplianceReport primary = null;
        int checked = 0;
        for (String target : targets) {
            checked++;
            RobotsComplianceReport current = checkUrl(target, checked);
            if (primary == null) primary = current;
            if (!current.allowed()) {
                RobotsComplianceReport blocked = new RobotsComplianceReport(false, current.status(), current.targetUrl(), current.robotsUrl(),
                    current.httpStatus(), current.matchedRule(), current.message(), targets.size(), current.checkedAt());
                return override(source, blocked);
            }
        }
        String message = "机器人协议检测通过，共检查 " + checked + " 个采集地址。";
        return new RobotsComplianceReport(true, "ALLOWED", source.entryUrl(),
            primary == null ? robotsUrl(source.entryUrl()) : primary.robotsUrl(),
            primary == null ? null : primary.httpStatus(), null, message, checked, Instant.now());
    }

    public RobotsComplianceReport checkUrl(String targetUrl) {
        return checkUrl(targetUrl, 1);
    }

    private RobotsComplianceReport checkUrl(String targetUrl, int checkedCount) {
        URI target;
        try {
            target = normalizedTarget(targetUrl);
        } catch (Exception ex) {
            return report(false, "INVALID_URL", targetUrl, null, null, null,
                BLOCKED_PREFIX + "采集地址不是有效的 HTTP(S) URL：" + targetUrl, checkedCount);
        }
        String robotsUrl = robotsUrl(target.toString());
        Policy policy = policy(URI.create(robotsUrl));
        if (!policy.available()) {
            String message = BLOCKED_PREFIX + policy.message() + " 采集已停止。协议地址：" + robotsUrl;
            return report(false, policy.status(), target.toString(), robotsUrl, policy.httpStatus(), null,
                message, checkedCount);
        }
        if (policy.notFound()) {
            return report(true, "NOT_FOUND", target.toString(), robotsUrl, policy.httpStatus(), null,
                "站点未提供 robots.txt（HTTP " + policy.httpStatus() + "），按 RFC 9309 视为未声明抓取限制。", checkedCount);
        }
        Match match = allowed(policy.content(), target, properties.getUserAgent());
        if (!match.allowed()) {
            String message = BLOCKED_PREFIX + "robots.txt 禁止 " + properties.getUserAgent() + " 访问 "
                + target + "，匹配规则：Disallow: " + match.rule() + "。采集已停止。协议地址：" + robotsUrl;
            return report(false, "DISALLOWED", target.toString(), robotsUrl, policy.httpStatus(), match.rule(),
                message, checkedCount);
        }
        return report(true, "ALLOWED", target.toString(), robotsUrl, policy.httpStatus(), match.rule(),
            "robots.txt 允许访问该采集地址。", checkedCount);
    }

    private Policy policy(URI robotsUri) {
        String key = origin(robotsUri);
        CachedPolicy cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAt() < Math.max(0, properties.getCacheTtlMillis())) {
            return cached.policy();
        }
        Policy loaded = fetch(robotsUri);
        cache.put(key, new CachedPolicy(loaded, now));
        return loaded;
    }

    private Policy fetch(URI robotsUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(robotsUri)
                .timeout(Duration.ofMillis(Math.max(1, properties.getRequestTimeoutMillis())))
                .header("Accept", "text/plain, text/*;q=0.9, */*;q=0.1")
                .header("User-Agent", properties.getUserAgent() + "/1.0")
                .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                response.body().close();
                return new Policy(true, true, status, "", "NOT_FOUND", null);
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                return new Policy(false, false, status, "", "HTTP_ERROR",
                    "robots.txt 返回 HTTP " + status + "，无法确认采集许可。");
            }
            int max = Math.max(1, properties.getMaxFileBytes());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(max + 1);
            }
            if (bytes.length > max) {
                return new Policy(false, false, status, "", "TOO_LARGE",
                    "robots.txt 超过允许的最大大小 " + max + " 字节，无法可靠解析。");
            }
            String content = decodeUtf8(bytes);
            return new Policy(true, false, status, content, "AVAILABLE", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Policy(false, false, null, "", "INTERRUPTED", "robots.txt 检测被中断，无法确认采集许可。");
        } catch (Exception ex) {
            return new Policy(false, false, null, "", "UNREACHABLE",
                "robots.txt 无法访问或解析，无法确认采集许可：" + safeMessage(ex));
        }
    }

    private Match allowed(String content, URI target, String productToken) {
        List<Group> groups = parse(content);
        String token = productToken == null ? "chatchat-newscollector" : productToken.toLowerCase(Locale.ROOT);
        int bestAgentLength = -1;
        List<Group> selected = new ArrayList<>();
        List<Group> wildcard = new ArrayList<>();
        for (Group group : groups) {
            int groupMatch = -1;
            for (String agent : group.agents()) {
                String normalized = agent.toLowerCase(Locale.ROOT);
                if ("*".equals(normalized)) {
                    wildcard.add(group);
                } else if (token.contains(normalized) || normalized.contains(token)) {
                    groupMatch = Math.max(groupMatch, normalized.length());
                }
            }
            if (groupMatch > bestAgentLength) {
                bestAgentLength = groupMatch;
                selected.clear();
                if (groupMatch >= 0) selected.add(group);
            } else if (groupMatch >= 0 && groupMatch == bestAgentLength) {
                selected.add(group);
            }
        }
        if (selected.isEmpty()) selected = wildcard;
        String path = target.getRawPath() == null || target.getRawPath().isBlank() ? "/" : target.getRawPath();
        if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
        Rule winner = null;
        for (Group group : selected) {
            for (Rule rule : group.rules()) {
                if (!matches(rule.path(), path)) continue;
                int length = rule.path().replace("*", "").replace("$", "").length();
                if (winner == null || length > winner.specificity()
                    || (length == winner.specificity() && rule.allow() && !winner.allow())) {
                    winner = new Rule(rule.allow(), rule.path(), length);
                }
            }
        }
        return winner == null ? new Match(true, null) : new Match(winner.allow(), winner.path());
    }

    private List<Group> parse(String content) {
        List<Group> groups = new ArrayList<>();
        MutableGroup current = null;
        boolean rulesStarted = false;
        for (String raw : content.split("\\R")) {
            String line = raw.replaceFirst("#.*$", "").trim();
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("user-agent".equals(field)) {
                if (current == null || rulesStarted) {
                    current = new MutableGroup();
                    groups.add(current.view());
                    rulesStarted = false;
                }
                if (!value.isBlank()) current.agents.add(value);
            } else if (("allow".equals(field) || "disallow".equals(field)) && current != null && !current.agents.isEmpty()) {
                rulesStarted = true;
                if (!value.isBlank() && (value.startsWith("/") || value.startsWith("*"))) {
                    current.rules.add(new Rule("allow".equals(field), value, 0));
                }
            }
        }
        return groups;
    }

    private boolean matches(String rule, String path) {
        boolean end = rule.endsWith("$");
        String value = end ? rule.substring(0, rule.length() - 1) : rule;
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            regex.append(ch == '*' ? ".*" : Pattern.quote(String.valueOf(ch)));
        }
        if (end) regex.append('$');
        return Pattern.compile(regex.toString()).matcher(path).find();
    }

    private void collectConfiguredUrls(Object value, Set<String> targets) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectConfiguredUrls(item, targets));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectConfiguredUrls(item, targets));
        } else if (value != null) {
            addUrl(targets, value.toString());
        }
    }

    private RobotsComplianceReport override(NewsSource source, RobotsComplianceReport blocked) {
        if (!booleanConfig(source, "robotsPolicyOverride")) return blocked;
        String reason = stringConfig(source, "robotsPolicyOverrideReason");
        Instant until = instantConfig(source, "robotsPolicyOverrideUntil");
        Instant now = Instant.now();
        if (reason == null || reason.length() < 10 || until == null || !until.isAfter(now)
            || until.isAfter(now.plus(7, ChronoUnit.DAYS))) return blocked;
        String message = "robots.txt 检测原本未通过，但该资讯源存在有效的临时人工豁免，允许继续采集。"
            + "豁免原因：" + reason + "；有效期至：" + until + "。原始检测结果：" + blocked.message();
        return new RobotsComplianceReport(true, "OVERRIDDEN", blocked.targetUrl(), blocked.robotsUrl(),
            blocked.httpStatus(), blocked.matchedRule(), message, blocked.checkedUrlCount(), Instant.now(),
            true, reason, until);
    }

    private boolean booleanConfig(NewsSource source, String key) {
        Object value = source.configuration().get(key);
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }

    private String stringConfig(NewsSource source, String key) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private Instant instantConfig(NewsSource source, String key) {
        String value = stringConfig(source, key);
        try { return value == null ? null : Instant.parse(value); }
        catch (Exception ignored) { return null; }
    }

    private void addUrl(Set<String> targets, String value) {
        if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) return;
        String normalized = value.replaceAll("\\{[^}]+}", "robots-probe");
        try {
            URI uri = normalizedTarget(normalized);
            targets.add(uri.toString());
        } catch (Exception ignored) { }
    }

    private URI normalizedTarget(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("HTTP(S) URL required");
        }
        return uri;
    }

    private String robotsUrl(String value) {
        URI uri = normalizedTarget(value);
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/robots.txt";
    }

    private String origin(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
    }

    private String decodeUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private RobotsComplianceReport report(boolean allowed, String status, String target, String robots,
                                           Integer httpStatus, String rule, String message, int checkedCount) {
        return new RobotsComplianceReport(allowed, status, target, robots, httpStatus, rule, message,
            checkedCount, Instant.now());
    }

    private record Policy(boolean available, boolean notFound, Integer httpStatus, String content,
                          String status, String message) { }
    private record CachedPolicy(Policy policy, long createdAt) { }
    private record Rule(boolean allow, String path, int specificity) { }
    private record Match(boolean allowed, String rule) { }
    private record Group(List<String> agents, List<Rule> rules) { }
    private static final class MutableGroup {
        private final List<String> agents = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private Group view() { return new Group(agents, rules); }
    }
}
