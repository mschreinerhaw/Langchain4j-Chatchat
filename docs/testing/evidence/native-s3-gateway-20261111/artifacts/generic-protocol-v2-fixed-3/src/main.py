"""
main.py - Entry point for the expense analysis pipeline.
Orchestrates loading, analysis, and report generation.
"""
import os
import sys

# Ensure src/ is importable when running from project root
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from analyzer import load_expenses, compute_statistics
from reporter import write_markdown_report, write_json_result, generate_chart


def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    csv_path = os.path.join(project_root, "data", "expenses.csv")
    output_dir = os.path.join(project_root, "output")
    os.makedirs(output_dir, exist_ok=True)

    print(f"[main] Loading CSV from: {csv_path}")
    df = load_expenses(csv_path)
    print(f"[main] Loaded {len(df)} rows.")

    print("[main] Computing statistics...")
    stats = compute_statistics(df)
    print(f"[main] rows={stats['rows']}  total={stats['total']}  mean={stats['mean']}")

    md_path = os.path.join(output_dir, "report.md")
    json_path = os.path.join(output_dir, "result.json")
    chart_path = os.path.join(output_dir, "chart.png")

    print(f"[main] Writing Markdown report -> {md_path}")
    write_markdown_report(stats, md_path)

    print(f"[main] Writing JSON result -> {json_path}")
    write_json_result(stats, json_path)

    print(f"[main] Generating chart -> {chart_path}")
    generate_chart(df, chart_path)

    print("[main] Done.")


if __name__ == "__main__":
    main()
