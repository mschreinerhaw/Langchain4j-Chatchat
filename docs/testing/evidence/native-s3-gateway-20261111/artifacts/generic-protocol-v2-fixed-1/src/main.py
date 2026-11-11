"""Main entry point for the synthetic expense analyzer."""

import sys
from pathlib import Path

# Ensure the project root is on sys.path so `src` is importable as a package.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from src.analyzer import load_expenses, compute_statistics
from src.reporter import write_markdown_report, write_json_result, write_chart


def main() -> None:
    csv_path = PROJECT_ROOT / "data" / "expenses.csv"
    out_dir = PROJECT_ROOT / "output"
    out_dir.mkdir(exist_ok=True)

    # 1. Load
    df = load_expenses(str(csv_path))
    print(f"Loaded {len(df)} rows from {csv_path}")

    # 2. Analyze
    stats = compute_statistics(df)
    print(f"Statistics: {stats}")

    # 3. Report
    write_markdown_report(stats, str(out_dir / "report.md"))
    print("Wrote output/report.md")

    # 4. JSON result (preserve arithmetic precision)
    data_records = df.to_dict(orient="records")
    write_json_result(stats, data_records, str(out_dir / "result.json"))
    print("Wrote output/result.json")

    # 5. Chart
    write_chart(df, str(out_dir / "chart.png"))
    print("Wrote output/chart.png")


if __name__ == "__main__":
    main()
