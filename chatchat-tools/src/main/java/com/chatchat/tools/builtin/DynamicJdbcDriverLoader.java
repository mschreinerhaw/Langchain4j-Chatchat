package com.chatchat.tools.builtin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.jar.JarFile;
import java.util.stream.Stream;

@Slf4j
@Component
public class DynamicJdbcDriverLoader {

    private final DatabaseToolProperties properties;
    private final AtomicReference<LoadedDrivers> loadedDrivers = new AtomicReference<>();
    private final ConcurrentMap<String, AtomicReference<LoadedDrivers>> scopedLoadedDrivers = new ConcurrentHashMap<>();

    /**
     * Creates a new DynamicJdbcDriverLoader instance.
     *
     * @param properties the properties value
     */
    public DynamicJdbcDriverLoader(DatabaseToolProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates the data source.
     *
     * @param jdbcUrl the jdbc url value
     * @param username the username value
     * @param password the password value
     * @param driverClass the driver class value
     * @return the created data source
     */
    public DataSource createDataSource(String jdbcUrl, String username, String password, String driverClass) {
        return createDataSource(jdbcUrl, username, password, driverClass, null);
    }

    public DataSource createDataSource(String jdbcUrl, String username, String password, String driverClass, String databaseType) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbc_url is required when querying an external database");
        }
        Driver driver = resolveDriver(jdbcUrl, driverClass, databaseType);
        Properties connectionProperties = new Properties();
        if (username != null && !username.isBlank()) {
            connectionProperties.put("user", username);
        }
        if (password != null) {
            connectionProperties.put("password", password);
        }
        return new DriverBackedDataSource(driver, jdbcUrl, connectionProperties);
    }

    /**
     * Performs the reload drivers operation.
     *
     * @return the operation result
     */
    public LoadedDriverSummary reloadDrivers() {
        LoadedDrivers current = loadedDrivers.getAndSet(null);
        if (current != null) {
            closeQuietly(current.classLoader());
        }
        scopedLoadedDrivers.values().forEach(reference -> {
            LoadedDrivers scoped = reference.getAndSet(null);
            if (scoped != null) {
                closeQuietly(scoped.classLoader());
            }
        });
        scopedLoadedDrivers.clear();
        LoadedDrivers loaded = loadDrivers();
        return new LoadedDriverSummary(properties.getDriverLibPath(), loaded.drivers().stream()
            .map(driver -> driver.getClass().getName())
            .toList());
    }

    /**
     * Resolves the driver.
     *
     * @param jdbcUrl the jdbc url value
     * @param driverClass the driver class value
     * @return the resolved driver
     */
    private Driver resolveDriver(String jdbcUrl, String driverClass, String databaseType) {
        if (driverClass != null && !driverClass.isBlank()) {
            return loadDriverClass(driverClass.trim(), databaseType);
        }
        try {
            return DriverManager.getDriver(jdbcUrl);
        } catch (SQLException ignored) {
            // Continue with external lib scanning.
        }
        LoadedDrivers loaded = loadDrivers(databaseType);
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
        loaded = loadDrivers(databaseType);
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

    /**
     * Loads the driver class.
     *
     * @param driverClass the driver class value
     * @return the operation result
     */
    private Driver loadDriverClass(String driverClass, String databaseType) {
        LoadedDrivers loaded = loadDrivers(databaseType);
        Throwable failure;
        try {
            return instantiateDriver(driverClass, loaded.classLoader());
        } catch (Throwable ex) {
            failure = ex;
        }
        reloadDrivers();
        loaded = loadDrivers(databaseType);
        try {
            return instantiateDriver(driverClass, loaded.classLoader());
        } catch (Throwable ex) {
            throw new IllegalArgumentException("Failed to load JDBC driver class " + driverClass + ": " + ex.getMessage(), failure);
        }
    }

    private Driver instantiateDriver(String driverClass, ClassLoader classLoader) throws Exception {
        Class<?> type = Class.forName(driverClass, true, classLoader);
        Object instance = type.getDeclaredConstructor().newInstance();
        if (!(instance instanceof Driver driver)) {
            throw new IllegalArgumentException(driverClass + " is not a java.sql.Driver");
        }
        return driver;
    }

    /**
     * Loads the drivers.
     *
     * @return the operation result
     */
    private LoadedDrivers loadDrivers() {
        return loadDrivers(null);
    }

    private LoadedDrivers loadDrivers(String databaseType) {
        String scope = normalizeScope(databaseType);
        if (scope != null) {
            return loadScopedDrivers(scope);
        }
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

    private LoadedDrivers loadScopedDrivers(String scope) {
        AtomicReference<LoadedDrivers> reference = scopedLoadedDrivers.computeIfAbsent(scope, ignored -> new AtomicReference<>());
        LoadedDrivers current = reference.get();
        if (current != null) {
            return current;
        }
        LoadedDrivers loaded = doLoadDrivers(scopedDriverLibPath(scope), scope);
        if (loaded.jarPaths().isEmpty()) {
            closeQuietly(loaded.classLoader());
            log.info("No scoped JDBC driver jars found for databaseType={} under {}; falling back to {}",
                scope, scopedDriverLibPath(scope), Path.of(properties.getDriverLibPath()).toAbsolutePath().normalize());
            return loadDrivers();
        }
        if (reference.compareAndSet(null, loaded)) {
            return loaded;
        }
        closeQuietly(loaded.classLoader());
        return reference.get();
    }

    /**
     * Performs the do load drivers operation.
     *
     * @return the operation result
     */
    private LoadedDrivers doLoadDrivers() {
        return doLoadDrivers(Path.of(properties.getDriverLibPath()).toAbsolutePath().normalize(), null);
    }

    private LoadedDrivers doLoadDrivers(Path libPath, String scope) {
        List<URL> urls = new ArrayList<>();
        List<Path> jarPaths = new ArrayList<>();
        if (Files.isDirectory(libPath)) {
            try (Stream<Path> stream = Files.list(libPath)) {
                stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            urls.add(path.toUri().toURL());
                            jarPaths.add(path);
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
        loadServiceDrivers(jarPaths, classLoader, drivers);
        if (scope == null) {
            log.info("Loaded {} external JDBC driver(s) from {}", drivers.size(), libPath);
        } else {
            log.info("Loaded {} scoped external JDBC driver(s) for databaseType={} from {}", drivers.size(), scope, libPath);
        }
        return new LoadedDrivers(classLoader, drivers, jarPaths);
    }

    private void loadServiceDrivers(List<Path> jarPaths, URLClassLoader classLoader, List<Driver> drivers) {
        for (Path jarPath : jarPaths) {
            for (String driverClass : serviceDriverClasses(jarPath)) {
                try {
                    Driver driver = instantiateDriver(driverClass, classLoader);
                    drivers.add(driver);
                    log.info("Loaded external JDBC driver {} from {}", driver.getClass().getName(), jarPath);
                } catch (Throwable ex) {
                    log.warn("Skipping external JDBC driver {} from {}: {}", driverClass, jarPath, rootMessage(ex));
                }
            }
        }
        if (!drivers.isEmpty()) {
            return;
        }
        try {
            ServiceLoader.load(Driver.class, classLoader).forEach(driver -> {
                drivers.add(driver);
                log.info("Loaded external JDBC driver {} via ServiceLoader", driver.getClass().getName());
            });
        } catch (Throwable ex) {
            log.warn("External JDBC ServiceLoader scan failed: {}. Pass driver_class to load a specific driver.", rootMessage(ex));
        }
    }

    private List<String> serviceDriverClasses(Path jarPath) {
        List<String> values = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entry = jarFile.getJarEntry("META-INF/services/java.sql.Driver");
            if (entry == null) {
                return values;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                jarFile.getInputStream(entry),
                StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.replaceFirst("#.*$", "").trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to read JDBC driver service descriptor from {}: {}", jarPath, ex.getMessage());
        }
        return values;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private Path scopedDriverLibPath(String scope) {
        return Path.of(properties.getDriverLibPath()).resolve(scope).toAbsolutePath().normalize();
    }

    private String normalizeScope(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            return null;
        }
        String normalized = databaseType.trim().toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
        if (normalized.isBlank() || "generic".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * Closes the quietly.
     *
     * @param classLoader the class loader value
     */
    private void closeQuietly(URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (Exception ignored) {
        }
    }

    private record LoadedDrivers(URLClassLoader classLoader, List<Driver> drivers, List<Path> jarPaths) {
    }

    public record LoadedDriverSummary(String driverLibPath, List<String> driverClasses) {
    }

    private static class DriverBackedDataSource implements DataSource {

        private final Driver driver;
        private final String jdbcUrl;
        private final Properties connectionProperties;
        private PrintWriter logWriter;
        private int loginTimeout;

        /**
         * Creates a new DynamicJdbcDriverLoader instance.
         *
         * @param driver the driver value
         * @param jdbcUrl the jdbc url value
         * @param connectionProperties the connection properties value
         */
        private DriverBackedDataSource(Driver driver, String jdbcUrl, Properties connectionProperties) {
            this.driver = driver;
            this.jdbcUrl = jdbcUrl;
            this.connectionProperties = connectionProperties;
        }

        /**
         * Returns the connection.
         *
         * @return the connection
         * @throws SQLException if the operation fails
         */
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = driver.connect(jdbcUrl, copyProperties(connectionProperties));
            if (connection == null) {
                throw new SQLException("JDBC driver " + driver.getClass().getName() + " did not accept URL " + jdbcUrl);
            }
            return connection;
        }

        /**
         * Returns the connection.
         *
         * @param username the username value
         * @param password the password value
         * @return the connection
         * @throws SQLException if the operation fails
         */
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

        /**
         * Returns the log writer.
         *
         * @return the log writer
         */
        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        /**
         * Sets the log writer.
         *
         * @param out the out value
         */
        @Override
        public void setLogWriter(PrintWriter out) {
            this.logWriter = out;
        }

        /**
         * Sets the login timeout.
         *
         * @param seconds the seconds value
         */
        @Override
        public void setLoginTimeout(int seconds) {
            this.loginTimeout = seconds;
        }

        /**
         * Returns the login timeout.
         *
         * @return the login timeout
         */
        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        /**
         * Returns the parent logger.
         *
         * @return the parent logger
         * @throws SQLFeatureNotSupportedException if the operation fails
         */
        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            try {
                return driver.getParentLogger();
            } catch (SQLFeatureNotSupportedException ex) {
                throw ex;
            }
        }

        /**
         * Performs the unwrap operation.
         *
         * @param iface the iface value
         * @return the operation result
         * @throws SQLException if the operation fails
         */
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

        /**
         * Returns whether is wrapper for.
         *
         * @param iface the iface value
         * @return whether the condition is satisfied
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this) || iface.isInstance(driver);
        }

        /**
         * Copies the properties.
         *
         * @param source the source value
         * @return the operation result
         */
        private Properties copyProperties(Properties source) {
            Properties copy = new Properties();
            copy.putAll(source);
            return copy;
        }
    }
}
