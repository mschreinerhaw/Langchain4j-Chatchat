package com.chatchat.license.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Prevents production deployments from using an empty, default or weak H2 database password. */
@Component
public class LicenseDatabaseSecurityValidator implements ApplicationRunner {
    private final String password;

    public LicenseDatabaseSecurityValidator(@Value("${spring.datasource.password:}") String password) {
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        boolean complex = password.length() >= 20
            && password.chars().anyMatch(Character::isUpperCase)
            && password.chars().anyMatch(Character::isLowerCase)
            && password.chars().anyMatch(Character::isDigit)
            && password.chars().anyMatch(value -> !Character.isLetterOrDigit(value))
            && !lower.contains("change-me") && !lower.contains("replace") && !lower.contains("password");
        if (!complex) {
            throw new IllegalStateException(
                "CHATCHAT_LICENSE_DB_PASSWORD 必须配置至少 20 位，并同时包含大写字母、小写字母、数字和特殊字符，禁止使用示例密码");
        }
    }
}
