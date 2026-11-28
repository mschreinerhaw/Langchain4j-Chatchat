package com.chatchat.api.agent.published;

import com.chatchat.api.runtime.coding.nativecline.NativeHtmlReport;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PublishedAgentHtml {
    static final MediaType TYPE = MediaType.parseMediaType("text/html; charset=UTF-8");
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(List.of(TablesExtension.create()))
        .escapeHtml(true).sanitizeUrls(true).build();

    private PublishedAgentHtml() {}

    static byte[] render(String markdown) {
        return ("""
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
            <title>Agent 报告</title>
            <style>
              body{margin:0;background:#f5f7fb;color:#233249;font:16px/1.65 system-ui,sans-serif}
              main{max-width:960px;margin:24px auto;padding:clamp(16px,4vw,40px);background:#fff;overflow:auto;overflow-wrap:anywhere}
              table{border-collapse:collapse}th,td{padding:.4em .6em;border:1px solid #dce5f2}th,pre{background:#f2f6fc}
              pre{padding:12px;white-space:pre-wrap}
            </style></head><body><main>
            """ + RENDERER.render(PARSER.parse(markdown)) + "</main></body></html>").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] entry(List<NativeHtmlReport.Resource> files, String entry) {
        return files.stream().filter(file -> entry.equals(file.path())).findFirst()
            .map(file -> Base64.getDecoder().decode(file.content())).orElseThrow();
    }

    static byte[] zip(List<NativeHtmlReport.Resource> files) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var file : files) {
                if (file.path().indexOf(':') >= 0) throw new IllegalArgumentException("Invalid report archive path");
                zip.putNextEntry(new ZipEntry(file.path()));
                zip.write(Base64.getDecoder().decode(file.content()));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
