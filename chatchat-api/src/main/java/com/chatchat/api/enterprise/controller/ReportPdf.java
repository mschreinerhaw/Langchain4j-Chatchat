package com.chatchat.api.enterprise.controller;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

final class ReportPdf {
    private static final long MAX_BYTES = 10 * 1024L * 1024;
    private static final long MAX_PIXELS = 50_000_000;
    private static final int MAX_PAGES = 32;

    private ReportPdf() {}

    static boolean supportsText(String text) {
        return watermarkFont().canDisplayUpTo(text) < 0;
    }

    private static Font watermarkFont() {
        return new Font(Font.SANS_SERIF, Font.BOLD, 36);
    }

    static byte[] render(List<MultipartFile> parts, String watermark) throws IOException {
        if (parts.isEmpty() || parts.size() > MAX_PAGES || parts.stream().mapToLong(MultipartFile::getSize).sum() > MAX_BYTES)
            throw new IllegalArgumentException("Report page or size limit exceeded");
        if (!watermark.isBlank() && !supportsText(watermark))
            throw new IOException("Watermark glyphs unavailable");
        long pixels = 0;
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var pageNumberFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (var part : parts) {
                try (var raw = part.getInputStream(); var input = new MemoryCacheImageInputStream(raw)) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IllegalArgumentException("Invalid report page image");
                    var reader = readers.next();
                    try {
                        if (!"JPEG".equalsIgnoreCase(reader.getFormatName()))
                            throw new IllegalArgumentException("Only JPEG report pages are accepted");
                        reader.setInput(input, true, true);
                        int width = reader.getWidth(0), height = reader.getHeight(0);
                        pixels += (long) width * height;
                        if (width > 2400 || height > 3400 || pixels > MAX_PIXELS)
                            throw new IllegalArgumentException("Report image dimensions exceed limit");
                        var page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        try (var stream = new PDPageContentStream(document, page)) {
                            float availableWidth = PDRectangle.A4.getWidth() - 68;
                            float availableHeight = PDRectangle.A4.getHeight() - 68;
                            var image = stamp(reader.read(0), watermark);
                            float scale = Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
                            var flattened = JPEGFactory.createFromImage(document, image, 0.94f);
                            stream.drawImage(flattened, (PDRectangle.A4.getWidth() - width * scale) / 2,
                                PDRectangle.A4.getHeight() - 34 - image.getHeight() * scale, width * scale, image.getHeight() * scale);
                            stream.beginText();
                            stream.setFont(pageNumberFont, 9);
                            stream.setNonStrokingColor(new Color(110, 120, 135));
                            stream.newLineAtOffset(PDRectangle.A4.getWidth() / 2 - 9, 14);
                            stream.showText(document.getNumberOfPages() + " / " + parts.size());
                            stream.endText();
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static BufferedImage stamp(BufferedImage input, String watermark) {
        if (watermark.isBlank()) return input;
        int width = input.getWidth(), height = input.getHeight();
        // Reserve a full-page watermark panel even when the final slice contains only a few rows.
        int markedHeight = Math.max(height, Math.round(width * 1.28f));
        var output = new BufferedImage(width, markedHeight, BufferedImage.TYPE_INT_RGB);
        var g = output.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, markedHeight);
            g.drawImage(input, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(65, 82, 110, 56));
            g.translate(width / 2.0, markedHeight / 2.0);
            g.rotate(Math.toRadians(-25));
            var font = watermarkFont().deriveFont(width * 0.052f);
            var longText = g.getFontMetrics(font).stringWidth(watermark);
            if (longText > width * 0.74f) font = font.deriveFont(font.getSize2D() * width * 0.74f / longText);
            g.setFont(font);
            int x = -g.getFontMetrics().stringWidth(watermark) / 2;
            for (int band = -1; band <= 1; band++)
                g.drawString(watermark, x, band * markedHeight / 4);
        } finally {
            g.dispose();
        }
        return output;
    }
}
