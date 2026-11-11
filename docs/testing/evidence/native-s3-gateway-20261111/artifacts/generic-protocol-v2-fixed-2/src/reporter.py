"""reporter.py — Reporting module.

Produces a Markdown report, a JSON result file, and a Matplotlib bar chart.
"""

import json
import matplotlib
matplotlib.use("Agg")  # non-interactive backend
import matplotlib.pyplot as plt
import pandas as pd


def write_markdown_report(stats: dict, path: str) -> None:
    """Write a human-readable Markdown report."""
    rows = stats["rows"]
    total = stats["total"]
    mean = stats["mean"]
    summary = stats["summary"]

    md = f"""# Synthetic Expense Analysis Report

> **Data status:** synthetic test fixture — not business data.

## Summary

{summary}

## Statistics

| Metric | Value |
|--------|------:|
| Rows   | {rows} |
| Total  | {total:.4f} |
| Mean   | {mean:.4f} |

*Figures are rounded to 4 decimal places for readability.*
"""
    with open(path, "w") as f:
        f.write(md)


def write_json_result(stats: dict, data: list[dict], path: str) -> None:
    """Write structured JSON with arithmetic precision preserved."""
    result = {
        "rows": stats["rows"],
        "total": stats["total"],
        "mean": stats["mean"],
        "dataStatus": "synthetic_test_fixture",
        "data": data,
    }
    with open(path, "w") as f:
        json.dump(result, f, indent=2)


def write_chart(df: pd.DataFrame, path: str) -> None:
    """Generate a bar chart of expenses and save as PNG."""
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar(df["label"], df["amount"], color=["#4c72b0", "#55a868", "#c44e52"])
    ax.set_xlabel("Label")
    ax.set_ylabel("Amount")
    ax.set_title("Synthetic Test Expenses")
    ax.set_ylim(0, max(df["amount"]) * 1.25)
    for i, v in enumerate(df["amount"]):
        ax.text(i, v + 0.3, f"{v}", ha="center", fontsize=9)
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)
