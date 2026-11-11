"""
reporter.py - Report generation module
Produces Markdown report, JSON result, and Matplotlib chart.
"""
import json
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def write_markdown_report(stats: dict, output_path: str) -> None:
    """Write a human-readable Markdown report."""
    rows = stats["rows"]
    total = stats["total"]
    mean = stats["mean"]
    records = stats["records"]

    lines = [
        "# Expense Analysis Report",
        "",
        "> **Note:** This report is generated from a *synthetic acceptance fixture*, "
        "not from customer or business data. No business validity is asserted.",
        "",
        "## Summary Statistics",
        "",
        f"| Metric | Value |",
        f"|---|---|",
        f"| Rows | {rows} |",
        f"| Total | {total:.2f} *(rounded)* |",
        f"| Mean | {mean:.2f} *(rounded)* |",
        "",
        "## Data Records",
        "",
        "| # | Label | Amount |",
        "|---|---|---|",
    ]
    for idx, rec in enumerate(records, start=1):
        lines.append(f"| {idx} | {rec['label']} | {rec['amount']:.2f} |")

    lines += [
        "",
        "*Figures displayed as rounded for human readability. "
        "Structured JSON statistics preserve full arithmetic precision.*",
    ]

    with open(output_path, "w") as f:
        f.write("\n".join(lines) + "\n")


def write_json_result(stats: dict, output_path: str) -> None:
    """Write structured JSON result with full arithmetic precision."""
    result = {
        "rows": stats["rows"],
        "total": stats["total"],
        "mean": stats["mean"],
        "dataStatus": "synthetic-test-fixture: not customer/business data; no business validity asserted"
    }
    with open(output_path, "w") as f:
        json.dump(result, f, indent=2)
        f.write("\n")


def generate_chart(df: pd.DataFrame, output_path: str) -> None:
    """Generate a bar chart of expenses and save as PNG."""
    fig, ax = plt.subplots(figsize=(7, 4))
    labels = df["label"].tolist()
    amounts = df["amount"].tolist()
    colors = ["#4C72B0", "#55A868", "#C44E52"]

    bars = ax.bar(labels, amounts, color=colors[: len(labels)])
    ax.set_title("Synthetic Test Expenses")
    ax.set_xlabel("Label")
    ax.set_ylabel("Amount")
    ax.axhline(y=sum(amounts) / len(amounts), color="gray", linestyle="--",
               label=f"Mean = {sum(amounts)/len(amounts):.2f}")
    ax.legend()

    for bar, val in zip(bars, amounts):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                f"{val}", ha="center", va="bottom", fontsize=10)

    fig.tight_layout()
    fig.savefig(output_path, dpi=120)
    plt.close(fig)
