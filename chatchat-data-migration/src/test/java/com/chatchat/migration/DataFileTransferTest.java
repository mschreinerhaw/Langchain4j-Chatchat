package com.chatchat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatchat.migration.DataMigrationApplication.Column;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.lang.reflect.Proxy;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataFileTransferTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesUnicodeBinaryAndRowCount() throws Exception {
        Path file = createArchive(false);
        DataFileTransfer.Header header = DataFileTransfer.validateArchive(file, "api").get("sample");
        assertEquals(1, header.rows());
        assertEquals("text", header.fields().get("description"));
        assertEquals("bytea", header.fields().get("attachment"));
    }

    @Test
    void rejectsCorruptTableChecksum() throws Exception {
        Path file = createArchive(true);
        assertThrows(IOException.class, () -> DataFileTransfer.validateArchive(file, "api"));
    }

    @Test
    void convertsMysqlTinyintArchiveValueToPostgresqlBoolean() throws Exception {
        AtomicReference<Boolean> bound = new AtomicReference<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setBoolean")) {
                        bound.set((Boolean) args[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DataFileTransfer.bindCell(statement, 1, new DataFileTransfer.Cell((byte) 5, null, "1", false),
                "boolean", DataMigrationApplication.Engine.POSTGRESQL, ZoneId.of("Asia/Shanghai"));
        assertTrue(bound.get());
        assertThrows(IllegalArgumentException.class, () -> DataFileTransfer.bindCell(statement, 1,
                new DataFileTransfer.Cell((byte) 5, null, "invalid", false), "boolean",
                DataMigrationApplication.Engine.POSTGRESQL, ZoneId.of("Asia/Shanghai")));
    }

    private Path createArchive(boolean corruptChecksum) throws Exception {
        Path file = temporaryDirectory.resolve(corruptChecksum ? "corrupt.zip" : "valid.zip");
        LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
        columns.put("description", new Column("description", "text"));
        columns.put("attachment", new Column("attachment", "bytea"));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("tables/sample.bin"));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream output = new DataOutputStream(new DigestOutputStream(zip, digest));
            DataFileTransfer.writeHeader(output, "sample", columns, 1);
            output.writeByte(5);
            DataFileTransfer.writeText(output, "中文说明");
            output.writeByte(1);
            DataFileTransfer.writeBytes(output, new byte[] {0, 1, -1});
            output.flush();
            zip.closeEntry();

            Properties manifest = new Properties();
            manifest.setProperty("format", "1");
            manifest.setProperty("module", "api");
            manifest.setProperty("tables", "sample");
            manifest.setProperty("table.sample.rows", "1");
            manifest.setProperty("table.sample.sha256", corruptChecksum ? "bad"
                    : HexFormat.of().formatHex(digest.digest()));
            zip.putNextEntry(new ZipEntry("manifest.properties"));
            manifest.store(zip, "test");
            zip.closeEntry();
        }
        return file;
    }
}
