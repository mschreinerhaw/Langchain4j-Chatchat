"""Main entry point: orchestrate analysis and reporting."""

import sys
import json
from pathlib import Path

# Ensure src is importable when running from workspace root
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.analyzer import load_expenses, compute_statistics
from src.reporter import write_markdown_report, write_json_result, generate_chart


def main() -> None:
    workspace = Path(__file__).resolve().parent.parent
    csv_path = workspace / "data" / "test_expenses.csv"
    output_dir = workspace / "output"
    output_dir.mkdir(exist_ok=True)

    # 1. Load & analyze
    df = load_expenses(str(csv_path))
    stats = compute_statistics(df)
    print(stats["summary_line"])

    # 2. Generate outputs
    write_markdown_report(stats, str(output_dir / "report.md"))
    write_json_result(stats, str(output_dir / "result.json"))
    generate_chart(df, str(output_dir / "chart.png"))

    # 3. Write delivery manifest
    manifest = {
        "schemaVersion": "coding_node_delivery_selection.v1",
        "artifacts": [
            {"path": "src/analyzer.py", "type": "SOURCE"},
            {"path": "src/reporter.py", "type": "SOURCE"},
            {"path": "src/main.py", "type": "SOURCE"},
            {"path": "output/report.md", "type": "REPORT"},
            {"path": "output/result.json", "type": "RESULT"},
            {"path": "output/chart.png", "type": "CHART"},
            {"path": "data/test_expenses.csv", "type": "AUXILIARY"},
        ],
    }
    manifest_path = workspace / "delivery_manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print("All artifacts written successfully.")


if __name__ == "__main__":
    main()
