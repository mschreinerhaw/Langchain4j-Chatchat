"""Reporter module: generates Markdown report, JSON result, and PNG chart."""

import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from pathlib import Path


def write_markdown_report(stats: dict, output_path: str) -> None:
    """Write a human-readable Markdown report."""
    rows = stats["rows"]
    total = stats["total"]
    mean = stats["mean"]
    data_status = stats["dataStatus"]

    md = f"""# Synthetic Expense Analysis Report

> **Data Status:** {data_status}

## Summary Statistics

| Metric | Value |
|--------|-------|
| Rows   | {rows} |
| Total  | {total:.4f} |
| Mean   | {mean:.4f} |

*Figures are rounded to 4 decimal places for readability. Exact values are preserved in `result.json`.*

## Note

This report is generated from a **synthetic acceptance fixture**. No business validity
is asserted. The data exists solely for testing and demonstration purposes.
"""
    Path(output_path).write_text(md, encoding="utf-8")


def write_json_result(stats: dict, output_path: str) -> None:
    """Write structured JSON result with arithmetic precision preserved."""
    result = {
        "rows": stats["rows"],
        "total": stats["total"],
        "mean": stats["mean"],
        "dataStatus": stats["dataStatus"],
    }
    Path(output_path).write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def generate_chart(df, output_path: str) -> None:
    """Generate a bar chart of expenses as PNG."""
    fig, ax = plt.subplots(figsize=(7, 4))
    labels = df["label"].tolist()
    amounts = df["amount"].tolist()
    colors = ["#4C72B0", "#55A868", "#C44E52"]
    bars = ax.bar(labels, amounts, color=colors[: len(labels)])
    ax.set_xlabel("Label")
    ax.set_ylabel("Amount")
    ax.set_title("Synthetic Test Expenses")
    for bar, val in zip(bars, amounts):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                f"{val}", ha="center", va="bottom", fontsize=9)
    ax.set_ylim(0, max(amounts) * 1.25)
    fig.tight_layout()
    fig.savefig(output_path, dpi=120)
    plt.close(fig)
