"""main.py - Entry point that orchestrates analysis and report generation."""

import sys
import os

# Ensure src/ is importable
sys.path.insert(0, os.path.dirname(__file__))

from analyze import load_expenses, compute_statistics, build_summary_line, save_result_json
from report import generate_markdown_report, generate_chart


BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(BASE_DIR, "data", "test_expenses.csv")
OUTPUT_DIR = os.path.join(BASE_DIR, "output")


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print("Loading expenses from:", CSV_PATH)
    df = load_expenses(CSV_PATH)
    print(f"Loaded {len(df)} rows.")

    stats = compute_statistics(df)
    print("Statistics:", stats)

    summary = build_summary_line(stats)
    print(summary)

    save_result_json(stats, os.path.join(OUTPUT_DIR, "result.json"))
    generate_markdown_report(df, stats, os.path.join(OUTPUT_DIR, "report.md"))
    generate_chart(df, os.path.join(OUTPUT_DIR, "chart.png"))

    print("All outputs written to:", OUTPUT_DIR)


if __name__ == "__main__":
    main()
