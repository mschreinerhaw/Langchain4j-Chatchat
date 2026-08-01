package com.chatchat.mcpserver.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DatabasePoolExhaustionReleaseTest {

    @Test
    void realMysqlPoolTimesOutWhenExhaustedAndRecoversAfterReleaseWithoutLeak() {
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            String container = "chatchat-pool-gate-" + UUID.randomUUID().toString().substring(0, 8);
            String image = System.getProperty("chatchat.e2e.mysql.image", "mysql:8.4");
            try {
                docker("run", "-d", "--name", container, "-p", "127.0.0.1::3306",
                    "-e", "MYSQL_DATABASE=release_gate", "-e", "MYSQL_USER=release_user",
                    "-e", "MYSQL_PASSWORD=release_password", "-e", "MYSQL_ROOT_PASSWORD=release_root_password",
                    image, "--mysql-native-password=ON");
                String portOutput = docker("port", container, "3306/tcp").trim();
                int port = Integer.parseInt(portOutput.substring(portOutput.lastIndexOf(':') + 1));
                String jdbcUrl = "jdbc:mysql://127.0.0.1:" + port
                    + "/release_gate?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
                awaitMysql(jdbcUrl);

                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbcUrl);
                config.setUsername("release_user");
                config.setPassword("release_password");
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setMaximumPoolSize(2);
                config.setMinimumIdle(0);
                config.setConnectionTimeout(300);
                config.setValidationTimeout(250);
                config.setPoolName("release-pool-exhaustion");

                try (HikariDataSource pool = new HikariDataSource(config);
                     Connection first = pool.getConnection();
                     Connection second = pool.getConnection()) {
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
                    assertThatThrownBy(pool::getConnection)
                        .isInstanceOf(SQLTransientConnectionException.class)
                        .hasMessageContaining("Connection is not available");

                    first.close();
                    try (Connection recovered = pool.getConnection();
                         var statement = recovered.createStatement();
                         var result = statement.executeQuery("SELECT 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                    second.close();
                    awaitNoActiveConnections(pool);
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                }
            } finally {
                dockerIgnoringFailure("rm", "-f", container);
            }
        });
    }

    private void awaitMysql(String jdbcUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(jdbcUrl, "release_user", "release_password")) {
                return;
            } catch (Exception ex) {
                last = ex;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("MySQL container did not become ready", last);
    }

    private String docker(String... arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(java.util.List.of(arguments));
        Process process = builder.command(command).redirectErrorStream(true).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Docker command failed: " + output.trim());
        }
        return output;
    }

    private void dockerIgnoringFailure(String... arguments) {
        try {
            docker(arguments);
        } catch (Exception ignored) {
            // A failed start may leave no container to remove.
        }
    }

    private void awaitNoActiveConnections(HikariDataSource pool) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (pool.getHikariPoolMXBean().getActiveConnections() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
