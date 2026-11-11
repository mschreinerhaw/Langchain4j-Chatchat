"""Reporter module: generates Markdown, JSON, and PNG outputs."""

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

> **Notice:** This report is generated from a **synthetic test fixture**.
> No business validity is asserted for the underlying data.

## Summary Statistics

| Metric | Value |
|--------|-------|
| Rows | {rows} |
| Total | {total:.4f} (rounded) |
| Mean | {mean:.4f} (rounded) |
| Data Status | `{data_status}` |

## Notes

- Figures above are rounded for human readability.
- Exact arithmetic values are preserved in the companion JSON result.
"""
    Path(output_path).write_text(md)


def write_json_result(stats: dict, data: list, output_path: str) -> None:
    """Write a machine-readable JSON result with exact arithmetic."""
    result = {
        "rows": stats["rows"],
        "total": stats["total"],
        "mean": stats["mean"],
        "dataStatus": stats["dataStatus"],
        "data": data,
    }
    Path(output_path).write_text(json.dumps(result, indent=2))


def write_chart(df, output_path: str) -> None:
    """Generate a bar chart of expenses and save as PNG."""
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar(df["label"], df["amount"], color=["#4c72b0", "#55a868", "#c44e52"])
    ax.set_xlabel("Label")
    ax.set_ylabel("Amount")
    ax.set_title("Synthetic Test Expenses")
    fig.tight_layout()
    fig.savefig(output_path, dpi=120)
    plt.close(fig)
