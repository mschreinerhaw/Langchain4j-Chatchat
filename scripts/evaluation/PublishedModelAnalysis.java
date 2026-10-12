import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.security.InternalSecretCipher;
import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;

/** Read-only offline adapter for deployed, published model connections. No Spring app is started. */
public class PublishedModelAnalysis {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path config = Path.of(args[0]);
        StandardEnvironment environment = new StandardEnvironment();
        for (String name : args[1].split(",")) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(config.resolve(name)));
            environment.getPropertySources().addFirst(new PropertiesPropertySource(name, yaml.getObject()));
        }
        Path envFile = config.resolve("env.properties");
        if (Files.exists(envFile)) {
            Properties props = new Properties();
            try (var input = Files.newInputStream(envFile)) { props.load(input); }
            environment.getPropertySources().addFirst(new PropertiesPropertySource("deployment-env", props));
        }
        String imported = environment.getProperty("spring.config.import", "");
        for (String reference : imported.split(",")) {
            String name = reference.trim().replaceFirst("^optional:", "");
            if (name.isBlank()) continue;
            Path file = config.resolve(name).toAbsolutePath().normalize();
            if (!file.startsWith(config.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Datasource import must be inside the supplied config directory");
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(file));
            environment.getPropertySources().addLast(new PropertiesPropertySource("import:" + name, yaml.getObject()));
        }
        String crypto = environment.getProperty("chatchat.models.crypto-key", "");
        if (crypto.isBlank()) {
            String path = environment.getProperty("chatchat.internal-credential.crypto-key-file", "");
            if (!path.isBlank()) {
                Path key = Path.of(path);
                if (!key.isAbsolute()) key = config.toAbsolutePath().getParent().resolve(key);
                crypto = Files.readString(key).trim();
            }
        }
        String url = environment.getRequiredProperty("spring.datasource.url");
        String username = environment.getRequiredProperty("spring.datasource.username");
        String password = InternalSecretCipher.decryptIfNecessary(
            environment.getRequiredProperty("spring.datasource.password"), crypto);
        // An inspection never exposes endpoints, users, passwords, keys or connection strings.
        if (args.length > 2 && args[2].equals("inspect")) {
            System.out.println(json.writeValueAsString(Map.of("datasourceConfigured", !url.isBlank(),
                "datasourceType", url.split(":", 3)[1], "cryptoConfigured", !crypto.isBlank())));
            return;
        }
        Map<?,?> request = json.readValue(System.in, Map.class);
        String model = String.valueOf(request.get("model"));
        ModelsConfig.ModelConnectionConfig connection = new ModelsConfig.ModelConnectionConfig();
        try (Connection db = DriverManager.getConnection(url, username, password)) {
            db.setReadOnly(true);
            try (PreparedStatement query = db.prepareStatement("SELECT provider_model,base_url,protocol,"
                + "api_key_cipher FROM platform_model_publication WHERE model_type='chat' AND enabled=TRUE AND name=?")) {
                query.setString(1, model);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Published model unavailable");
                    connection.setModelName(row.getString("provider_model"));
                    connection.setBaseUrl(row.getString("base_url"));
                    connection.setProtocol(row.getString("protocol"));
                    connection.setApiKey(InternalSecretCipher.decryptIfNecessary(row.getString("api_key_cipher"), crypto));
                }
            }
        }
        connection.setTimeout(600000);
        connection.setMaxTokens(8192);
        connection.setMaxRetries(0);
        ModelsConfig models = new ModelsConfig();
        models.setAvailableChatModels(List.of(model));
        models.setChatModels(Map.of(model, connection));
        models.setDefaultChatModel(model);
        var generator = new ConfigurableChatModelFactory(models, json).create(model);
        List<ChatMessage> messages = new ArrayList<>();
        for (Object raw : (List<?>)request.get("messages")) {
            Map<?,?> message = (Map<?,?>)raw;
            String content = String.valueOf(message.get("content"));
            messages.add("system".equals(message.get("role")) ? SystemMessage.from(content) : UserMessage.from(content));
        }
        var response = generator.chat(ChatRequest.builder().messages(messages).build());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("answer", response.aiMessage().text());
        result.put("finishReason", String.valueOf(response.finishReason()));
        if (response.tokenUsage() != null) result.put("tokenUsage", Map.of(
            "input", response.tokenUsage().inputTokenCount(), "output", response.tokenUsage().outputTokenCount()));
        System.out.println(json.writeValueAsString(result));
    }
}
