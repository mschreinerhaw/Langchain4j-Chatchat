package com.chatchat.license.server;

import com.chatchat.license.LicenseDocument;
import com.chatchat.license.LicenseException;
import com.chatchat.license.LicensePayload;
import com.chatchat.license.MachineIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LicenseAuditService {
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ObjectMapper objectMapper;

    public LicenseAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LicenseAuditRecord recordIssued(LicensePayload requested, byte[] documentContent, String operator) {
        try {
            LicenseDocument document = objectMapper.readValue(documentContent, LicenseDocument.class);
            LicensePayload payload = document.payload();
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            String modulesJson = objectMapper.writeValueAsString(payload.modules() == null ? List.of() : payload.modules());
            jdbcTemplate.update("""
                INSERT INTO license_issue_audit (
                    id, license_no, customer_code, product, edition, server_id, max_users, max_agents,
                    modules_json, issued_date, expire_date, key_id, status, issued_by, issued_at,
                    download_count, last_downloaded_at, document_sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?, 0, NULL, ?)
                """,
                id, payload.licenseNo(), payload.customerCode(), payload.product(), payload.edition(),
                MachineIdentity.normalizeMac(payload.serverId()), payload.maxUsers(), payload.maxAgents(), modulesJson,
                sqlDate(payload.issuedTime()), sqlDate(payload.expireTime()), document.keyId(),
                safeOperator(operator), Timestamp.from(now), sha256(documentContent));
            return find(id);
        } catch (LicenseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LicenseException("License 已生成，但写入授权审计失败", ex);
        }
    }

    @Transactional
    public LicenseAuditRecord markDownloaded(String id) {
        int changed = jdbcTemplate.update("""
            UPDATE license_issue_audit
               SET status = 'DELIVERED', download_count = download_count + 1, last_downloaded_at = ?
             WHERE id = ?
            """, Timestamp.from(Instant.now()), id);
        if (changed == 0) throw new LicenseException("授权审计记录不存在");
        return find(id);
    }

    public LicenseAuditPage search(String keyword, String status, String edition, String module,
                                   LocalDate dateFrom, LocalDate dateTo, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        String normalizedEdition = edition == null ? "" : edition.trim().toLowerCase(Locale.ROOT);
        String normalizedModule = module == null ? "" : module.trim();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        String predicate = """
             WHERE (:keyword = '' OR LOWER(license_no) LIKE :keywordPattern
                    OR LOWER(COALESCE(customer_code, '')) LIKE :keywordPattern
                    OR LOWER(server_id) LIKE :keywordPattern OR LOWER(issued_by) LIKE :keywordPattern)
               AND (:status = '' OR status = :status)
               AND (:edition = '' OR LOWER(edition) = :edition)
               AND (:module = '' OR modules_json LIKE :modulePattern)
               AND (:dateFrom IS NULL OR issued_at >= :dateFrom)
               AND (:dateTo IS NULL OR issued_at < :dateTo)
            """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("keyword", normalizedKeyword)
            .addValue("keywordPattern", "%" + normalizedKeyword + "%")
            .addValue("status", normalizedStatus)
            .addValue("edition", normalizedEdition)
            .addValue("module", normalizedModule)
            .addValue("modulePattern", "%\"" + normalizedModule + "\"%")
            .addValue("dateFrom", dateFrom == null ? null : Timestamp.valueOf(dateFrom.atStartOfDay()))
            .addValue("dateTo", dateTo == null ? null : Timestamp.valueOf(dateTo.plusDays(1).atStartOfDay()))
            .addValue("limit", safeSize)
            .addValue("offset", safePage * safeSize);
        Long total = namedJdbcTemplate.queryForObject("SELECT COUNT(*) FROM license_issue_audit" + predicate,
            parameters, Long.class);
        List<LicenseAuditRecord> content = namedJdbcTemplate.query(
            "SELECT * FROM license_issue_audit" + predicate + " ORDER BY issued_at DESC LIMIT :limit OFFSET :offset",
            parameters, (rs, rowNum) -> mapRecord(rs));
        AuditSummary summary = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN status = 'DELIVERED' THEN 1 ELSE 0 END), 0) AS delivered_count,
                   COALESCE(SUM(CASE WHEN status = 'ISSUED' THEN 1 ELSE 0 END), 0) AS pending_count,
                   COALESCE(SUM(download_count), 0) AS download_count
              FROM license_issue_audit
            """, (rs, rowNum) -> new AuditSummary(rs.getLong("total_count"), rs.getLong("delivered_count"),
            rs.getLong("pending_count"), rs.getLong("download_count")));
        long totalElements = total == null ? 0 : total;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        return new LicenseAuditPage(content, safePage, safeSize, totalElements, totalPages, summary);
    }

    public LicenseAuditRecord find(String id) {
        return searchById(id).stream().findFirst().orElseThrow(() -> new LicenseException("授权审计记录不存在"));
    }

    private List<LicenseAuditRecord> searchById(String id) {
        return jdbcTemplate.query("SELECT * FROM license_issue_audit WHERE id = ?", (rs, rowNum) -> mapRecord(rs), id);
    }

    private LicenseAuditRecord mapRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LicenseAuditRecord(
            rs.getString("id"), rs.getString("license_no"), rs.getString("customer_code"),
            rs.getString("product"), rs.getString("edition"), rs.getString("server_id"),
            nullableInteger(rs, "max_users"), nullableInteger(rs, "max_agents"),
            readModules(rs.getString("modules_json")), toLocalDate(rs.getDate("issued_date")),
            toLocalDate(rs.getDate("expire_date")), rs.getString("key_id"), rs.getString("status"),
            rs.getString("issued_by"), rs.getTimestamp("issued_at").toInstant(),
            rs.getInt("download_count"), toInstant(rs.getTimestamp("last_downloaded_at")),
            rs.getString("document_sha256"));
    }

    private List<String> readModules(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Date sqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private static LocalDate toLocalDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private static Instant toInstant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String safeOperator(String value) { return value == null || value.isBlank() ? "license-admin" : value; }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    public record LicenseAuditRecord(
        String id, String licenseNo, String customerCode, String product, String edition, String serverId,
        Integer maxUsers, Integer maxAgents, List<String> modules, LocalDate issuedDate, LocalDate expireDate,
        String keyId, String status, String issuedBy, Instant issuedAt, int downloadCount,
        Instant lastDownloadedAt, String documentSha256
    ) { }

    public record AuditSummary(long total, long delivered, long pending, long downloads) { }
    public record LicenseAuditPage(List<LicenseAuditRecord> content, int page, int size,
                                   long totalElements, int totalPages, AuditSummary summary) { }
}
