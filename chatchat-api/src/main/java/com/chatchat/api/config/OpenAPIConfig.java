package com.chatchat.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration
 */
@Configuration
public class OpenAPIConfig {

    /**
     * Performs the custom open api operation.
     *
     * @return the operation result
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("ChatChat API")
                .version("1.0.0")
                .description("Enterprise-grade document search and Agent AI application powered by LangChain4j")
                .contact(new Contact()
                    .name("ChatChat Team")
                    .url("https://github.com/chatchat-space/Langchain-Chatchat"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}
