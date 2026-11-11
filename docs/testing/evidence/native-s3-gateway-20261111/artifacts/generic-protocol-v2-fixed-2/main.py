"""main.py — Entry point for the synthetic expense analysis pipeline."""

import os
import sys

# Ensure project root is on sys.path so `src` is importable
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, PROJECT_ROOT)

from src.analyzer import load_expenses, compute_statistics
from src.reporter import write_markdown_report, write_json_result, write_chart


def main() -> None:
    csv_path = os.path.join(PROJECT_ROOT, "data", "expenses.csv")
    out_dir = os.path.join(PROJECT_ROOT, "output")
    os.makedirs(out_dir, exist_ok=True)

    print(f"[main] Loading expenses from {csv_path}")
    df = load_expenses(csv_path)
    print(f"[main] Loaded {len(df)} rows")

    print("[main] Computing statistics …")
    stats = compute_statistics(df)
    print(f"[main] {stats['summary']}")

    data_records = df.to_dict(orient="records")

    md_path = os.path.join(out_dir, "report.md")
    write_markdown_report(stats, md_path)
    print(f"[main] Markdown report → {md_path}")

    json_path = os.path.join(out_dir, "result.json")
    write_json_result(stats, data_records, json_path)
    print(f"[main] JSON result   → {json_path}")

    chart_path = os.path.join(out_dir, "chart.png")
    write_chart(df, chart_path)
    print(f"[main] Chart PNG     → {chart_path}")

    print("[main] Done.")


if __name__ == "__main__":
    main()
