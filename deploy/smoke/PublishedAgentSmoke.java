import com.chatchat.common.security.InternalSecretCipher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PublishedAgentSmoke {
    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern TASK_ID = Pattern.compile("\\\"taskId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern STATUS = Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern TERMINAL = Pattern.compile("\\\"terminal\\\"\\s*:\\s*(true|false)");

    public static void main(String[] args) throws Exception {
        Path appHome = Path.of(args.length > 0 ? args[0] : "deploy/chatchat-1.0.0-SNAPSHOT").toAbsolutePath();
        String config = Files.readString(appHome.resolve("config/application-dev.yml"), StandardCharsets.UTF_8);
        String username = capture(config, Pattern.compile("(?ms)^  internal-credential:\\R.*?^    username:\\s*([^\\s]+)"));
        String encrypted = capture(config, Pattern.compile("(?ms)^  internal-credential:\\R.*?^    encrypted-secret:\\s*\\\"?([^\\\"\\r\\n]+)"));
        String key = Files.readString(appHome.resolve("config/internal-credential.key"), StandardCharsets.UTF_8).trim();
        String password = InternalSecretCipher.decryptIfNecessary(encrypted, key);
        boolean adminSmoke = args.length > 1 && "--admin".equals(args[1]);
        if (adminSmoke) {
            username = "admin";
            password = "123456";
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String loginBody = "{\"username\":" + json(username) + ",\"password\":" + json(password) + "}";
        String login = send(client, "POST", "/api/v1/enterprise/auth/login", loginBody, null);
        String token = capture(login, TOKEN);

        String question = "\u67e5\u8be2\u5ba2\u6237\u53f7070200046604\u7684\u4ea4\u6613\u548c\u8d44\u4ea7\u3001\u76c8\u4e8f\u60c5\u51b5.\u603b\u7ed3\u8fd9\u4e2a\u5ba2\u6237\u7684\u4ea4\u6613\u504f\u597d";
        String requestBody = "{\"question\":" + json(question)
            + ",\"sessionId\":" + json("ids-smoke-" + System.currentTimeMillis())
            + ",\"idempotencyKey\":" + json("ids-smoke-" + System.nanoTime())
            + ",\"historyWindow\":0,\"imageAnalysisIds\":[],\"parameters\":{}}";
        Instant started = Instant.now();
        String taskId;
        if (adminSmoke && args.length > 2 && !args[2].isBlank()) {
            taskId = args[2].trim();
        } else if (args.length > 1 && !adminSmoke && !args[1].isBlank()) {
            taskId = args[1].trim();
        } else {
            String submission = send(client, "POST", "/api/v1/published-agents/financial_indicator/questions", requestBody, token);
            taskId = capture(submission, TASK_ID);
        }
        System.out.println("TASK_ID=" + taskId);

        String lastStatus = "";
        while (Duration.between(started, Instant.now()).toSeconds() <= 450) {
            String answer;
            try {
                answer = send(client, "GET", "/api/v1/published-agents/financial_indicator/questions/" + taskId + "/answer", null, token);
            } catch (HttpTimeoutException timeout) {
                System.out.println("POLL_TIMEOUT elapsedSeconds=" + Duration.between(started, Instant.now()).toSeconds());
                Thread.sleep(5_000);
                continue;
            }
            String status = optionalCapture(answer, STATUS);
            long elapsed = Duration.between(started, Instant.now()).toSeconds();
            if (!status.equals(lastStatus) || elapsed % 60 < 20) {
                System.out.println("ELAPSED_SECONDS=" + elapsed + " STATUS=" + status);
                lastStatus = status;
            }
            if ("true".equals(optionalCapture(answer, TERMINAL))) {
                Path output = appHome.resolve("logs/ids-reasoning-arc-smoke-" + taskId + ".json");
                Files.writeString(output, answer, StandardCharsets.UTF_8);
                System.out.println("TERMINAL=true");
                System.out.println("RESULT_FILE=" + output);
                return;
            }
            Thread.sleep(15_000);
        }
        System.out.println("TERMINAL=false");
        System.exit(3);
    }

    private static String send(HttpClient client, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json; charset=UTF-8")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        }
        return response.body();
    }

    private static String capture(String source, Pattern pattern) {
        String value = optionalCapture(source, pattern);
        if (value.isEmpty()) throw new IllegalStateException("Expected response/config value not found");
        return value;
    }

    private static String optionalCapture(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
