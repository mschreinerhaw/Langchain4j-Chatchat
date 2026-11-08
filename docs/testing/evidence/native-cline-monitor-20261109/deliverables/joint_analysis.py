import csv
import json
import os
from decimal import Decimal

CSV_PATH = "/data/input/c48330a4f62e7d926d60e473ceb3adc03e6f71ec9221985d6729d3446af6307a/original-0.csv"
SOURCE_SHA256 = "fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9"
DATASET_REF = "user-asset:4874c4d755f8baeb9118b90e25e3f2fb2e8bfef8c7dee5728eaf077636f9c0a6:fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9"
OUTPUT_DIR = "/workspace/output/results"

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    rows = []
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)

    row_count = len(rows)
    value_sum = sum(Decimal(r["value"]) for r in rows)

    verified_summary = {
        "rowCount": row_count,
        "sum": str(value_sum),
        "sourceSha256": SOURCE_SHA256,
    }

    report_md = (
        "# Joint Monitor CSV Analysis Report\n\n"
        f"- **Source file**: native-monitor-joint.csv (original-0.csv)\n"
        f"- **SHA-256**: {SOURCE_SHA256}\n"
        f"- **Data row count**: {row_count}\n"
        f"- **Exact Decimal sum of `value`**: {value_sum}\n\n"
        "## Rows\n\n"
        "| category | value |\n"
        "|----------|-------|\n"
    )
    for r in rows:
        report_md += f"| {r['category']} | {r['value']} |\n"

    report_md += (
        "\n## Verification\n\n"
        "The row count and Decimal sum were computed directly from the immutable "
        "original CSV bytes delivered by Runtime.\n"
    )

    field_provenance = [
        {
            "datasetReference": DATASET_REF,
            "sourceField": "value",
            "meaning": "Literal numeric column from the original CSV; summed with Python Decimal for exact arithmetic.",
            "declarationSource": "nativeFileSchemas in schema.json, columns=[category, value]",
            "status": "CONFIRMED",
        }
    ]

    result_row = {
        "reportMarkdown": report_md,
        "evidenceReferences": [
            "user-asset:4874c4d755f8baeb9118b90e25e3f2fb2e8bfef8c7dee5728eaf077636f9c0a6:fcb74b46f4042569e233515f15ea92342b8e019dd9531e0e9b7e4c92d37e9fb9"
        ],
        "limitations": [
            "Analysis limited to the single authorized original CSV; no additional datasets or transport reconstruction."
        ],
        "analysisStatus": "COMPLETE",
        "fieldProvenance": field_provenance,
        "verifiedSummary": verified_summary,
    }

    with open(os.path.join(OUTPUT_DIR, "rows.jsonl"), "w", encoding="utf-8") as f:
        f.write(json.dumps(result_row, ensure_ascii=False) + "\n")

    with open(os.path.join(OUTPUT_DIR, "report.md"), "w", encoding="utf-8") as f:
        f.write(report_md)

    print(f"rows.jsonl written: rowCount={row_count}, sum={value_sum}")

if __name__ == "__main__":
    main()
