package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.store.NewsBulkIndexer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsAttachmentIngestionServiceTest {
    private HttpServer server;
    private NewsAttachmentIngestionService service;

    @AfterEach void stop() {
        if (service != null) service.close();
        if (server != null) server.stop(0);
    }

    @Test
    void downloadsParsesAndChunksExcelAttachmentOnBoundedWorker() throws Exception {
        byte[] attachment;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Report");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Company"); header.createCell(1).setCellValue("Revenue");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("TestCorp"); data.createCell(1).setCellValue(120000);
            workbook.write(output); attachment = output.toByteArray();
        }
        NewsDocument chunk = process("report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", attachment);
        assertThat(chunk.content()).contains("TestCorp", "120000");
        assertThat(chunk.metadata()).containsEntry("documentKind", "attachment_chunk")
            .containsEntry("parentDocumentId", "news-parent")
            .containsEntry("parentTitle", "年度报告")
            .containsEntry("evidenceTitle", "年度报告")
            .containsEntry("attachmentFileName", "report.xlsx");
    }

    @Test
    void downloadsParsesAndChunksPdfAttachment() throws Exception {
        byte[] attachment;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Annual report revenue 120000 and net profit 18000");
                content.endText();
            }
            pdf.save(output); attachment = output.toByteArray();
        }
        NewsDocument chunk = process("report.pdf", "application/pdf", attachment);
        assertThat(chunk.content()).contains("Annual report revenue", "net profit 18000");
        assertThat(chunk.metadata()).containsEntry("attachmentFileName", "report.pdf");
    }

    @Test
    void detectsAndParsesDocxAttachmentWithoutFileExtension() throws Exception {
        byte[] attachment;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Board resolution approves the acquisition and capital increase");
            document.write(output);
            attachment = output.toByteArray();
        }

        NewsDocument chunk = process("download",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", attachment);

        assertThat(chunk.content()).contains("Board resolution", "capital increase");
        assertThat(chunk.metadata()).containsEntry("attachmentFileName", "board-resolution.docx");
    }

    private NewsDocument process(String fileName, String contentType, byte[] attachment) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            if ("download".equals(fileName)) {
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"board-resolution.docx\"");
            }
            exchange.sendResponseHeaders(200, attachment.length);
            exchange.getResponseBody().write(attachment);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/" + fileName;
        NewsRuntimeProperties.Attachment properties = new NewsRuntimeProperties.Attachment();
        properties.setChunkSize(200);
        NewsBulkIndexer bulkIndexer = mock(NewsBulkIndexer.class);
        when(bulkIndexer.submit(any())).thenReturn(true);
        service = new NewsAttachmentIngestionService(properties, bulkIndexer, HttpClient.newHttpClient());
        NewsDocument parent = new NewsDocument("news-parent", 1L, "公告源", NewsSourceType.WEB_LIST,
            "年度报告", "公司发布年度报告正文。", null, null, "http://localhost/article", Instant.now(), Instant.now(),
            "zh-CN", List.of("公告"), List.of(), "hash", NewsAnalysisStatus.PENDING,
            Map.of("attachmentUrls", List.of(url), "attachmentAllowedDomains", List.of("localhost")));

        service.submit(parent);

        ArgumentCaptor<NewsDocument> captor = ArgumentCaptor.forClass(NewsDocument.class);
        verify(bulkIndexer, timeout(5000)).submit(captor.capture());
        return captor.getValue();
    }
}
