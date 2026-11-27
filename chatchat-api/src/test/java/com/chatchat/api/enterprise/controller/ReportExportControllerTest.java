package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ReportExportControllerTest {
    private final SysRoleRepository roles = mock(SysRoleRepository.class);
    private final SysAuditLogRepository audits = mock(SysAuditLogRepository.class);
    private final ReportExportSource sources = mock(ReportExportSource.class);
    private final ReportPrintClient printer = mock(ReportPrintClient.class);
    private final EnterpriseAdminService identities = mock(EnterpriseAdminService.class);
    private ReportExportController controller;

    @BeforeEach
    void initialize() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table sys_tenant(id varchar(64) primary key)");
        jdbc.update("insert into sys_tenant values ('tenant-a'), ('tenant-b')");
        controller = new ReportExportController(mock(WorkspaceService.class), roles, audits, jdbc, new ObjectMapper(),
            sources, printer, identities);
        controller.initialize();
        var role = new SysRole();
        role.setId("reader");
        role.setStatus("enabled");
        when(roles.findByTenantIdOrderByRoleNameAsc("tenant-a")).thenReturn(List.of(role));
    }

    @Test
    void exportRequiresTenantRoleAndFlattensWatermarkOnEveryPage() throws Exception {
        var manager = request("tenant-a", true, "admin");
        var user = request("tenant-a", false, "reader");
        var otherTenant = request("tenant-b", false, "reader");
        var options = new ReportExportController.Policy(true, "内部报告", Set.of("reader"), 0);
        var reference = new ReportExportSource.Reference("report-123", null, null);

        assertThat(controller.capability(manager).getData()).isEqualTo(java.util.Map.of("allowed", false));
        assertThat(controller.settings(manager).getData().toString()).contains("企业内部资料 · 严禁外传");
        assertThatThrownBy(() -> controller.pdf(user, reference)).isInstanceOf(ResponseStatusException.class);
        verify(audits).saveAndFlush(argThat(event -> "denied".equals(event.getResult())));
        controller.save(manager, options);
        assertThatThrownBy(() -> controller.pdf(otherTenant, reference)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.save(user, options)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.save(manager, options)).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(sources, printer);
        var source = new ReportExportSource.Document("index.html", List.of());
        when(sources.load(user, reference)).thenReturn(source);
        when(printer.print(source)).thenReturn(pages());
        when(identities.isActiveIdentity("tenant-a", "user")).thenReturn(true);
        when(identities.getUserView("user")).thenReturn((UserView) user.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW));
        var response = controller.pdf(user, reference);
        byte[] bytes = response.getBody();
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            var renderer = new PDFRenderer(pdf);
            for (int index = 0; index < 2; index++) {
                var image = renderer.renderImageWithDPI(index, 115);
                long marked = 0;
                for (int y = 160; y < image.getHeight() - 180; y++) {
                    for (int x = 100; x < image.getWidth() - 100; x++) {
                        if ((image.getRGB(x, y) & 255) < 248) marked++;
                    }
                }
                assertThat(marked).isGreaterThan(1000);
                if (index == 0 && System.getProperty("report.test.preview") != null) {
                    var folder = Path.of(System.getProperty("report.test.preview"));
                    Files.createDirectories(folder);
                    ImageIO.write(image, "PNG", folder.resolve("report-watermark.png").toFile());
                    Files.write(folder.resolve("report-watermark.pdf"), bytes);
                }
            }
        }
        var audit = org.mockito.ArgumentCaptor.forClass(SysAuditLog.class);
        verify(audits, atLeastOnce()).saveAndFlush(audit.capture());
        var success = audit.getAllValues().stream().filter(event -> "success".equals(event.getResult())
            && "pdf".equals(event.getActionName())).findFirst().orElseThrow();
        assertThat(success.getResourceId()).isEqualTo("report-123");
        assertThat(success.getTenantId()).isEqualTo("tenant-a");
        var detail = new ObjectMapper().readTree(success.getDetail());
        assertThat(detail.path("requestId").asText()).isEqualTo(response.getHeaders().getFirst("X-Report-Export-Request-Id"));
        assertThat(detail.path("transferStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(detail.path("sha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void invalidRendererResponseDoesNotProducePdf() throws IOException {
        var user = request("tenant-a", false, "reader");
        var reference = new ReportExportSource.Reference("bad-report", null, null);
        var source = new ReportExportSource.Document("index.html", List.of());
        when(sources.load(user, reference)).thenReturn(source);
        when(printer.print(source)).thenReturn(new byte[]{1, 2, 3});
        controller.save(request("tenant-a", true), new ReportExportController.Policy(false, "", Set.of("reader"), 0));
        assertThatThrownBy(() -> controller.pdf(user, reference))
            .isInstanceOf(ResponseStatusException.class);
        verify(audits).saveAndFlush(argThat(event -> "failure".equals(event.getResult())));
    }

    private static byte[] pages() throws IOException {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static MockHttpServletRequest request(String tenant, boolean manager, String... roles) {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW, new UserView("user", tenant, null, null, null,
            "report-user", null, null, null, "enabled", null, List.of(roles),
            manager ? List.of(ReportExportController.MANAGE_PERMISSION) : List.of(), null, null, false));
        return request;
    }
}
