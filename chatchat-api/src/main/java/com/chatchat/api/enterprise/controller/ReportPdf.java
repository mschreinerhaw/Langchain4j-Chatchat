package com.chatchat.api.enterprise.controller;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class ReportPdf {
    private static final int DPI = 110;
    private static final long MAX_PIXELS = 50_000_000;
    private static final int MAX_PAGES = 32;

    private ReportPdf() {}

    static boolean supportsText(String text) {
        return watermarkFont().canDisplayUpTo(text) < 0;
    }

    private static Font watermarkFont() {
        return new Font(Font.SANS_SERIF, Font.BOLD, 36);
    }

    record Output(byte[] bytes, int pages) {}

    static Output render(byte[] rendered, String watermark) throws IOException {
        if (!watermark.isBlank() && !supportsText(watermark))
            throw new IOException("Watermark glyphs unavailable");
        try (var source = Loader.loadPDF(rendered); var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            int count = source.getNumberOfPages();
            if (count < 1 || count > MAX_PAGES) throw new IllegalArgumentException("Report page limit exceeded");
            var renderer = new PDFRenderer(source);
            renderer.setSubsamplingAllowed(true);
            var pageNumberFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            long pixels = 0;
            for (int index = 0; index < count; index++) {
                var size = source.getPage(index).getCropBox();
                if (size.getWidth() <= 0 || size.getHeight() <= 0 || size.getWidth() > 1000 || size.getHeight() > 1000)
                    throw new IllegalArgumentException("Unexpected printed page dimensions");
                pixels += (long) Math.ceil(size.getWidth() * DPI / 72) * (long) Math.ceil(size.getHeight() * DPI / 72);
                if (pixels > MAX_PIXELS) throw new IllegalArgumentException("Report image dimensions exceed limit");
                var image = stamp(renderer.renderImageWithDPI(index, DPI), watermark);
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    float pageWidth = PDRectangle.A4.getWidth(), pageHeight = PDRectangle.A4.getHeight();
                    float scale = Math.min(pageWidth / image.getWidth(), (pageHeight - 16) / image.getHeight());
                    var flattened = JPEGFactory.createFromImage(document, image, 0.94f);
                    stream.drawImage(flattened, (pageWidth - image.getWidth() * scale) / 2,
                        pageHeight - image.getHeight() * scale, image.getWidth() * scale, image.getHeight() * scale);
                    stream.beginText();
                    stream.setFont(pageNumberFont, 9);
                    stream.setNonStrokingColor(new Color(110, 120, 135));
                    stream.newLineAtOffset(pageWidth / 2 - 9, 10);
                    stream.showText((index + 1) + " / " + count);
                    stream.endText();
                }
            }
            document.save(bytes);
            return new Output(bytes.toByteArray(), count);
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
