package com.chatchat.common.migration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.datasource-migration")
public class DataSourceMigrationProperties {

    /**
     * Enables one-shot H2 to MySQL data migration on application startup.
     */
    private boolean enabled = false;

    /**
     * Source H2 JDBC URL.
     */
    private String sourceUrl = "jdbc:h2:file:./data/h2/chatchat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

    /**
     * Source H2 JDBC driver.
     */
    private String sourceDriverClassName = "org.h2.Driver";

    /**
     * Source H2 username.
     */
    private String sourceUsername = "sa";

    /**
     * Source H2 password.
     */
    private String sourcePassword = "";

    /**
     * Delete target table rows before copying. Keep false to protect existing MySQL data.
     */
    private boolean replaceExisting = false;

    /**
     * Maximum rows inserted per JDBC batch.
     */
    private int batchSize = 500;
}
