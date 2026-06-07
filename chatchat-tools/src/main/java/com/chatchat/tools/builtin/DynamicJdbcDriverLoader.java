package com.chatchat.tools.builtin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Slf4j
@Component
public class DynamicJdbcDriverLoader {

    private final DatabaseToolProperties properties;
    private final AtomicReference<LoadedDrivers> loadedDrivers = new AtomicReference<>();

    public DynamicJdbcDriverLoader(DatabaseToolProperties properties) {
        this.properties = properties;
    }

    public DataSource createDataSource(String jdbcUrl, String username, String password, String driverClass) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbc_url is required when querying an external database");
        }
        Driver driver = resolveDriver(jdbcUrl, driverClass);
        Properties connectionProperties = new Properties();
        if (username != null && !username.isBlank()) {
            connectionProperties.put("user", username);
        }
        if (password != null) {
            connectionProperties.put("password", password);
        }
        return new DriverBackedDataSource(driver, jdbcUrl, connectionProperties);
    }

    public LoadedDriverSummary reloadDrivers() {
        LoadedDrivers current = loadedDrivers.getAndSet(null);
        if (current != null) {
            closeQuietly(current.classLoader());
        }
        LoadedDrivers loaded = loadDrivers();
        return new LoadedDriverSummary(properties.getDriverLibPath(), loaded.drivers().stream()
            .map(driver -> driver.getClass().getName())
            .toList());
    }

    private Driver resolveDriver(String jdbcUrl, String driverClass) {
        if (driverClass != null && !driverClass.isBlank()) {
            return loadDriverClass(driverClass.trim());
        }
        try {
            return DriverManager.getDriver(jdbcUrl);
        } catch (SQLException ignored) {
            // Continue with external lib scanning.
        }
        LoadedDrivers loaded = loadDrivers();
        for (Driver driver : loaded.drivers()) {
            try {
                if (driver.acceptsURL(jdbcUrl)) {
                    return driver;
                }
            } catch (SQLException ex) {
                log.debug("JDBC driver {} rejected URL check: {}", driver.getClass().getName(), ex.getMessage());
            }
        }
        reloadDrivers();
        loaded = loadedDrivers.get();
        for (Driver driver : loaded.drivers()) {
            try {
                if (driver.acceptsURL(jdbcUrl)) {
                    return driver;
                }
            } catch (SQLException ex) {
                log.debug("JDBC driver {} rejected URL check after reload: {}", driver.getClass().getName(), ex.getMessage());
            }
        }
        throw new IllegalArgumentException("No JDBC driver found for URL. Put the driver jar in "
            + properties.getDriverLibPath() + " or pass driver_class.");
    }

    private Driver loadDriverClass(String driverClass) {
        LoadedDrivers loaded = loadDrivers();
        try {
            Class<?> type = Class.forName(driverClass, true, loaded.classLoader());
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalArgumentException(driverClass + " is not a java.sql.Driver");
            }
            return driver;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to load JDBC driver class " + driverClass + ": " + ex.getMessage(), ex);
        }
    }

    private LoadedDrivers loadDrivers() {
        LoadedDrivers current = loadedDrivers.get();
        if (current != null) {
            return current;
        }
        LoadedDrivers loaded = doLoadDrivers();
        if (loadedDrivers.compareAndSet(null, loaded)) {
            return loaded;
        }
        closeQuietly(loaded.classLoader());
        return loadedDrivers.get();
    }

    private LoadedDrivers doLoadDrivers() {
        Path libPath = Path.of(properties.getDriverLibPath()).toAbsolutePath().normalize();
        List<URL> urls = new ArrayList<>();
        if (Files.isDirectory(libPath)) {
            try (Stream<Path> stream = Files.list(libPath)) {
                stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            urls.add(path.toUri().toURL());
                        } catch (Exception ex) {
                            log.warn("Failed to add JDBC driver jar {}: {}", path, ex.getMessage());
                        }
                    });
            } catch (Exception ex) {
                log.warn("Failed to scan JDBC driver lib path {}: {}", libPath, ex.getMessage());
            }
        } else {
            log.info("JDBC driver lib path does not exist yet: {}", libPath);
        }

        URLClassLoader classLoader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        List<Driver> drivers = new ArrayList<>();
        ServiceLoader.load(Driver.class, classLoader).forEach(driver -> {
            drivers.add(driver);
            log.info("Loaded external JDBC driver {} from {}", driver.getClass().getName(), libPath);
        });
        return new LoadedDrivers(classLoader, drivers);
    }

    private void closeQuietly(URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (Exception ignored) {
        }
    }

    private record LoadedDrivers(URLClassLoader classLoader, List<Driver> drivers) {
    }

    public record LoadedDriverSummary(String driverLibPath, List<String> driverClasses) {
    }

    private static class DriverBackedDataSource implements DataSource {

        private final Driver driver;
        private final String jdbcUrl;
        private final Properties connectionProperties;
        private PrintWriter logWriter;
        private int loginTimeout;

        private DriverBackedDataSource(Driver driver, String jdbcUrl, Properties connectionProperties) {
            this.driver = driver;
            this.jdbcUrl = jdbcUrl;
            this.connectionProperties = connectionProperties;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = driver.connect(jdbcUrl, copyProperties(connectionProperties));
            if (connection == null) {
                throw new SQLException("JDBC driver " + driver.getClass().getName() + " did not accept URL " + jdbcUrl);
            }
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Properties properties = copyProperties(connectionProperties);
            properties.put("user", username);
            properties.put("password", password);
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("JDBC driver " + driver.getClass().getName() + " did not accept URL " + jdbcUrl);
            }
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            this.logWriter = out;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            this.loginTimeout = seconds;
        }

        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            try {
                return driver.getParentLogger();
            } catch (SQLFeatureNotSupportedException ex) {
                throw ex;
            }
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            if (iface.isInstance(driver)) {
                return iface.cast(driver);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this) || iface.isInstance(driver);
        }

        private Properties copyProperties(Properties source) {
            Properties copy = new Properties();
            copy.putAll(source);
            return copy;
        }
    }
}
