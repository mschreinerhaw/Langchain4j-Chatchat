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

    static String guide(PublishedAgentApiController.PublishedAgentCurlExample example) {
        String report = "/api/v1/published-agents/" + example.agentId() + "/questions/${TASK_ID}/report";
        return """
            # Agent API 对接说明

            Agent ID `%s`

            ## 调用协议

            使用管理员签发的 Agent API Token，随请求发送 `Authorization: Bearer <token>`。
            下方示例需要 curl 和 jq。提交问答返回 HTTP 202，从 `data.taskId` 提取任务编号，
            轮询 `status` 直到 `data.terminal` 为真，再通过 `answer` 获取最终结果。
            状态接口支持 `afterSequence` 增量读取事件。任务仅对发起者开放。

            ## HTML 报告

            请求体的 `reportMode` 默认为 `visual`，开启图文报告并优先交付 HTML。
            有原生归档时交付原报告，纯文本结果交付由正文渲染的 HTML。
            使用 `"reportMode":"off"` 关闭报告，仍可获取文本答案。
            任务有报告时，答案中的 `data.report.downloadUrl` 给出 HTML 下载路径，
            `data.report.bundleUrl` 给出包含本地资源的 ZIP 路径。
            原生报告引用本地脚本、样式或图片时，下载 ZIP，解压后按 `data.report.entryPath` 打开 HTML 入口。
            集成到网页时，请在禁用同源权限的沙箱中展示生成的 HTML。
            报告接口必须使用 Agent API Token，报告未就绪或关闭时返回 HTTP 404。

            ```sh
            curl --fail --show-error --header "Authorization: Bearer ${AGENT_TOKEN}" \\
              "${AGENT_BASE_URL}%s" --output report.html
            curl --fail --show-error --header "Authorization: Bearer ${AGENT_TOKEN}" \\
              "${AGENT_BASE_URL}%s?bundle=true" --output report.zip
            ```

            ## 完整调用示例

            ```sh
            %s
            ```
            """.formatted(example.agentId(), report, report, example.completeExample());
    }
}
