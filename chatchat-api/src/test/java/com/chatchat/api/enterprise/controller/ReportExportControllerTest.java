package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService.UserView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private ReportExportController controller;

    @BeforeEach
    void initialize() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table sys_tenant(id varchar(64) primary key)");
        jdbc.update("insert into sys_tenant values ('tenant-a'), ('tenant-b')");
        controller = new ReportExportController(mock(WorkspaceService.class), roles, audits, jdbc, new ObjectMapper());
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
        var page = page(650, 800);

        assertThatThrownBy(() -> controller.pdf(user, "report-123", List.of(page))).isInstanceOf(ResponseStatusException.class);
        verify(audits).saveAndFlush(argThat(event -> "denied".equals(event.getResult())));
        controller.save(manager, options);
        assertThatThrownBy(() -> controller.pdf(otherTenant, "report-123", List.of(page))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.save(user, options)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.save(manager, options)).isInstanceOf(ResponseStatusException.class);

        var response = controller.pdf(user, "report-123", List.of(page, page));
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
    void invalidRasterDoesNotProducePdf() {
        var file = new MockMultipartFile("pages", "fake.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> controller.pdf(request("tenant-a", true), "bad-image", List.of(file)))
            .isInstanceOf(ResponseStatusException.class);
        verify(audits).saveAndFlush(argThat(event -> "failure".equals(event.getResult())));
    }

    private static MockMultipartFile page(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", bytes);
        return new MockMultipartFile("pages", "page.jpg", "image/jpeg", bytes.toByteArray());
    }

    private static MockHttpServletRequest request(String tenant, boolean manager, String... roles) {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW, new UserView("user", tenant, null, null, null,
            "report-user", null, null, null, "enabled", null, List.of(roles),
            manager ? List.of(ReportExportController.MANAGE_PERMISSION) : List.of(), null, null, false));
        return request;
    }
}
