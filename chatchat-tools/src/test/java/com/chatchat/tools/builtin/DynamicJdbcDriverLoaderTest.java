package com.chatchat.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicJdbcDriverLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDriverFromDatabaseTypeSubdirectory() throws Exception {
        Path driverRoot = tempDir.resolve("drivers");
        Path scopedRoot = driverRoot.resolve("dm");
        Files.createDirectories(scopedRoot);
        createScopedDriverJar(scopedRoot.resolve("scoped-driver.jar"));

        DatabaseToolProperties properties = new DatabaseToolProperties();
        properties.setDriverLibPath(driverRoot.toString());
        DynamicJdbcDriverLoader loader = new DynamicJdbcDriverLoader(properties);

        assertThat(loader.createDataSource("jdbc:scoped:demo", "", "", "", "dm")).isNotNull();
        assertThatThrownBy(() -> loader.createDataSource("jdbc:scoped:demo", "", "", "", "kingbase"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No JDBC driver found");
    }

    private void createScopedDriverJar(Path jarPath) throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path classRoot = tempDir.resolve("classes");
        Path packageDir = sourceRoot.resolve("testdriver");
        Files.createDirectories(packageDir);
        Files.createDirectories(classRoot);

        Path source = packageDir.resolve("ScopedDriver.java");
        Files.writeString(source, """
            package testdriver;

            import java.sql.Connection;
            import java.sql.Driver;
            import java.sql.DriverPropertyInfo;
            import java.sql.SQLException;
            import java.util.Properties;
            import java.util.logging.Logger;

            public class ScopedDriver implements Driver {
                @Override
                public Connection connect(String url, Properties info) {
                    return null;
                }

                @Override
                public boolean acceptsURL(String url) {
                    return url != null && url.startsWith("jdbc:scoped:");
                }

                @Override
                public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                    return new DriverPropertyInfo[0];
                }

                @Override
                public int getMajorVersion() {
                    return 1;
                }

                @Override
                public int getMinorVersion() {
                    return 0;
                }

                @Override
                public boolean jdbcCompliant() {
                    return false;
                }

                @Override
                public Logger getParentLogger() {
                    return Logger.getGlobal();
                }
            }
            """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        int exitCode = compiler.run(null, null, null, "-d", classRoot.toString(), source.toString());
        assertThat(exitCode).isZero();

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("testdriver/ScopedDriver.class"));
            Files.copy(classRoot.resolve("testdriver").resolve("ScopedDriver.class"), jar);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jar.write("testdriver.ScopedDriver\n".getBytes());
            jar.closeEntry();
        }
    }
}
