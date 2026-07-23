package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.storage.FinancialDataStore;
import com.chatchat.runtime.market.storage.FinancialDataStore.SecurityMasterRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports the official SSE and SZSE stock/depositary-receipt directories. */
@Service
public class SecurityMasterImportService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SecurityMasterImportService.class);
    private static final String SSE_PAGE = "https://www.sse.com.cn/assortment/stock/list/share/";
    private static final String SSE_API = "https://query.sse.com.cn/sseQuery/commonQuery.do";
    private static final String SZSE_PAGE = "https://www.szse.cn/market/product/stock/list/index.html";
    private static final String SZSE_API = "https://www.szse.cn/api/report/ShowReport/data";

    private final FinancialDataStore store;
    private final ObjectMapper mapper;

    public SecurityMasterImportService(FinancialDataStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.securityMasterCount() > 0) return;
        try {
            refresh();
        } catch (Exception ex) {
            log.warn("Initial official security-master import failed; the next scheduled refresh will retry", ex);
        }
    }

    @Scheduled(cron = "${chatchat.mcp.market.security-master.refresh-cron:0 20 5 * * *}",
        zone = "Asia/Shanghai")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception ex) {
            log.warn("Scheduled official security-master refresh failed; existing rows were retained", ex);
        }
    }

    public synchronized ImportResult refresh() throws Exception {
        List<SecurityMasterRecord> sse = fetchSse();
        List<SecurityMasterRecord> szse = fetchSzse();
        if (sse.isEmpty() || szse.isEmpty()) {
            throw new IllegalStateException("Official security directory returned an empty exchange");
        }
        int sseCount = store.replaceSecurityMaster("SSE", sse);
        int szseCount = store.replaceSecurityMaster("SZSE", szse);
        log.info("Official security master refreshed: SSE={}, SZSE={}", sseCount, szseCount);
        return new ImportResult(sseCount, szseCount, sseCount + szseCount, java.time.Instant.now());
    }

    private List<SecurityMasterRecord> fetchSse() throws Exception {
        Map<String, SecurityMasterRecord> records = new LinkedHashMap<>();
        for (String stockType : List.of("1", "2", "8")) {
            String url = SSE_API + "?sqlId=COMMON_SSE_CP_GPJCTPZ_GPLB_GP_L"
                + "&STOCK_TYPE=" + stockType
                + "&REG_PROVINCE=&CSRC_CODE=&STOCK_CODE=&COMPANY_STATUS=2%2C4%2C5%2C7%2C8"
                + "&type=inParams&isPagination=true&pageHelp.cacheSize=1&pageHelp.beginPage=1"
                + "&pageHelp.pageSize=5000&pageHelp.pageNo=1";
            JsonNode result = getJson(url, SSE_PAGE).path("result");
            if (!result.isArray()) continue;
            for (JsonNode row : result) {
                String code = text(row, "2".equals(stockType) ? "B_STOCK_CODE" : "A_STOCK_CODE");
                String name = normalizeName(text(row, "SEC_NAME_CN"));
                if (code.isBlank() || "-".equals(code) || name.isBlank()) continue;
                String board = switch (stockType) {
                    case "2" -> "主板B股";
                    case "8" -> "科创板";
                    default -> "主板A股";
                };
                String securityType = code.startsWith("689") ? "DR" : "STOCK";
                records.put(code, new SecurityMasterRecord(code, name, text(row, "FULL_NAME"),
                    securityType, board, date(text(row, "LIST_DATE")), text(row, "CSRC_CODE_DESC"), SSE_PAGE));
            }
        }
        return List.copyOf(records.values());
    }

    private List<SecurityMasterRecord> fetchSzse() throws Exception {
        Map<String, SecurityMasterRecord> records = new LinkedHashMap<>();
        fetchSzseTab("tab1", "agdm", "agjc", "agssrq", "STOCK", records);
        fetchSzseTab("tab2", "bgdm", "bgjc", "bgssrq", "STOCK", records);
        fetchSzseTab("tab3", "cdrdm", "cdrjc", "cdrssrq", "DR", records);
        return List.copyOf(records.values());
    }

    private void fetchSzseTab(String tab, String codeField, String nameField, String dateField,
                              String securityType, Map<String, SecurityMasterRecord> target) throws Exception {
        JsonNode first = getJson(szseUrl(tab, 1), SZSE_PAGE);
        JsonNode section = section(first, tab);
        int pageCount = section.path("metadata").path("pagecount").asInt(0);
        collectSzseRows(section.path("data"), codeField, nameField, dateField, securityType, target);
        for (int page = 2; page <= pageCount; page++) {
            JsonNode pageSection = section(getJson(szseUrl(tab, page), SZSE_PAGE), tab);
            collectSzseRows(pageSection.path("data"), codeField, nameField, dateField, securityType, target);
        }
    }

    private void collectSzseRows(JsonNode rows, String codeField, String nameField, String dateField,
                                 String securityType, Map<String, SecurityMasterRecord> target) {
        if (!rows.isArray()) return;
        for (JsonNode row : rows) {
            String code = text(row, codeField);
            String name = normalizeName(stripHtml(text(row, nameField)));
            if (code.isBlank() || name.isBlank()) continue;
            target.put(code, new SecurityMasterRecord(code, name, null, securityType,
                normalizeName(text(row, "bk")), date(text(row, dateField)),
                normalizeName(text(row, "sshymc")), SZSE_PAGE));
        }
    }

    private JsonNode section(JsonNode response, String tab) {
        if (response.isArray()) {
            for (JsonNode section : response) {
                if (tab.equals(section.path("metadata").path("tabkey").asText())) return section;
            }
        }
        return mapper.createObjectNode();
    }

    private String szseUrl(String tab, int page) {
        return SZSE_API + "?SHOWTYPE=JSON&CATALOGID=1110&TABKEY=" + tab + "&PAGENO=" + page;
    }

    private JsonNode getJson(String url, String referer) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json,text/javascript,*/*;q=0.8");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "close");
                connection.setRequestProperty("Referer", referer);
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/138.0 Safari/537.36");
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (var body = connection.getInputStream()) {
                        return mapper.readTree(body);
                    }
                }
                lastFailure = new IOException("Official exchange request failed with HTTP " + status);
                if (status < 500 && status != 429) break;
            } catch (IOException ex) {
                lastFailure = ex;
            } finally {
                if (connection != null) connection.disconnect();
            }
            Thread.sleep(250L * attempt);
        }
        throw lastFailure == null ? new IOException("Official exchange request failed") : lastFailure;
    }

    private String text(JsonNode row, String field) {
        return row.path(field).asText("").trim();
    }

    private LocalDate date(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        String digits = value.replaceAll("[^0-9]", "");
        try {
            if (digits.length() >= 8) {
                return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                    Integer.parseInt(digits.substring(4, 6)), Integer.parseInt(digits.substring(6, 8)));
            }
        } catch (RuntimeException ignored) {
            // Preserve a usable security row even if an exchange publishes an unexpected date.
        }
        return null;
    }

    private String stripHtml(String value) {
        return value.replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private String normalizeName(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        for (char ch : value.trim().toCharArray()) {
            if (Character.isWhitespace(ch)) continue;
            if (ch >= 0xFF01 && ch <= 0xFF5E) normalized.append((char) (ch - 0xFEE0));
            else normalized.append(ch);
        }
        return normalized.toString().trim();
    }

    public record ImportResult(int sseCount, int szseCount, int totalCount, java.time.Instant refreshedAt) { }
}
