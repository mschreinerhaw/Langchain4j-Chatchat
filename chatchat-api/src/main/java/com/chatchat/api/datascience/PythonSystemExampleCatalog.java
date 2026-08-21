package com.chatchat.api.datascience;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class PythonSystemExampleCatalog {
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"source_file":{"type":"FILE","description":"需要分析的数据文件"},"limit":{"type":"integer","default":100}},"required":["source_file"],"additionalProperties":false}
            """;
    private final List<Example> examples = List.of(
            table("csv", "CSV", "csv_analysis.py", "sample_sales.csv", "pd.read_csv(source_file)", List.of("pandas")),
            table("xls", "XLS", "xls_analysis.py", "sample_sales.xls", "pd.read_excel(source_file, engine=\"xlrd\")", List.of("pandas", "xlrd")),
            table("xlsx", "XLSX", "xlsx_analysis.py", "sample_sales.xlsx", "pd.read_excel(source_file, engine=\"openpyxl\")", List.of("pandas", "openpyxl")),
            table("json", "JSON", "json_analysis.py", "sample_events.json", "pd.read_json(source_file)", List.of("pandas")),
            text("txt", "TXT", "txt_analysis.py", "sample_notes.txt", false),
            text("log", "LOG", "log_analysis.py", "sample_application.log", true),
            table("parquet", "PARQUET", "parquet_analysis.py", "sample_sales.parquet", "pd.read_parquet(source_file)", List.of("pandas", "pyarrow")),
            table("orc", "ORC", "orc_analysis.py", "sample_sales.orc", "pd.read_orc(source_file)", List.of("pandas", "pyarrow")),
            zip()
    );

    public List<Example> list() {
        return examples;
    }

    public Example get(String id) {
        return examples.stream().filter(e -> e.id().equalsIgnoreCase(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("系统示例不存在"));
    }

    public byte[] data(String id) {
        Example example = get(id);
        try {
            return new ClassPathResource("python-examples/data/" + example.dataFileName()).getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("系统示例数据不可用：" + example.dataFileName(), ex);
        }
    }

    private static Example table(String id, String format, String script, String data, String reader, List<String> requirements) {
        return new Example(id, format, format + " 数据分析", script, data, "读取 " + format + " 数据，返回字段、行数、缺失值和数值汇总。", requirements, INPUT_SCHEMA, """
                import json
                import os
                import pandas as pd
                
                params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))
                source_file = params.get("source_file")
                limit = max(1, int(params.get("limit", 100)))
                if not source_file:
                    raise ValueError("缺少 source_file；请在运行参数中绑定文件")
                
                df = %s
                preview = df.head(limit)
                result = {
                    "format": "%s",
                    "rows": int(len(df)),
                    "columns": [str(value) for value in df.columns],
                    "missing": {str(key): int(value) for key, value in df.isna().sum().items()},
                    "numeric_summary": df.describe(include="number").replace({float("nan"): None}).to_dict(),
                    "preview": preview.where(preview.notna(), None).to_dict(orient="records")
                }
                print(json.dumps(result, ensure_ascii=False, default=str))
                """.formatted(reader, format));
    }

    private static Example text(String id, String format, String script, String data, boolean log) {
        String analysis = log ? """
                import re
                levels = {"ERROR": 0, "WARN": 0, "INFO": 0, "DEBUG": 0}
                for line in lines:
                    match = re.search(r"\\b(ERROR|WARN|INFO|DEBUG)\\b", line, re.IGNORECASE)
                    if match:
                        levels[match.group(1).upper()] += 1
                extra = {"levels": levels}
                """ : """
                words = [word for line in lines for word in line.split()]
                extra = {"characters": sum(len(line) for line in lines), "words": len(words)}
                """;
        return new Example(id, format, format + " 文本分析", script, data, "逐行读取 " + format + " 文件并输出基础统计。", List.of(), INPUT_SCHEMA, """
                import json
                import os
                
                params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))
                source_file = params.get("source_file")
                limit = max(1, int(params.get("limit", 100)))
                if not source_file:
                    raise ValueError("缺少 source_file；请在运行参数中绑定文件")
                with open(source_file, "r", encoding="utf-8", errors="replace") as stream:
                    lines = stream.readlines()
                %s
                result = {"format": "%s", "lines": len(lines), "preview": [line.rstrip("\\n") for line in lines[:limit]], **extra}
                print(json.dumps(result, ensure_ascii=False))
                """.formatted(analysis, format));
    }

    private static Example zip() {
        return new Example("zip", "ZIP", "ZIP 压缩包检查", "zip_analysis.py", "sample_bundle.zip", "安全检查 ZIP 内容并列出文件、压缩大小和未压缩大小。", List.of(), INPUT_SCHEMA, """
                import json
                import os
                import zipfile
                
                params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))
                source_file = params.get("source_file")
                limit = max(1, int(params.get("limit", 100)))
                if not source_file:
                    raise ValueError("缺少 source_file；请在运行参数中绑定文件")
                with zipfile.ZipFile(source_file) as archive:
                    members = []
                    for item in archive.infolist()[:limit]:
                        normalized = item.filename.replace("\\\\", "/")
                        unsafe = normalized.startswith("/") or ".." in normalized.split("/")
                        members.append({"name": item.filename, "size": item.file_size, "compressed_size": item.compress_size, "unsafe_path": unsafe})
                print(json.dumps({"format": "ZIP", "entries": len(members), "members": members}, ensure_ascii=False))
                """);
    }

    public record Example(String id, String format, String name, String scriptFileName, String dataFileName,
                          String description, List<String> requirements, String inputSchema, String sourceCode) {
    }
}
