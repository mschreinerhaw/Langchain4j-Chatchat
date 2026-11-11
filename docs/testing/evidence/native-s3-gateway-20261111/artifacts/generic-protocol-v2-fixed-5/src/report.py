"""report.py - Report generation module (Markdown + Matplotlib chart)."""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def generate_markdown_report(df: pd.DataFrame, stats: dict, output_path: str) -> None:
    """Write a Markdown report summarizing the synthetic test expenses."""
    rounded_total = round(stats["total"], 2)
    rounded_mean = round(stats["mean"], 2)

    lines = [
        "# Synthetic Test Expense Report",
        "",
        "> **Notice:** This report analyzes a synthetic test fixture — not real business data.",
        "",
        "## Summary Statistics",
        "",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Rows   | {stats['rows']} |",
        f"| Total  | {rounded_total} (rounded) |",
        f"| Mean   | {rounded_mean} (rounded) |",
        f"| Data Status | {stats['dataStatus']} |",
        "",
        "## Expense Breakdown",
        "",
        "| Label | Amount |",
        "|-------|--------|",
    ]
    for _, row in df.iterrows():
        lines.append(f"| {row['label']} | {row['amount']} |")

    lines.append("")
    lines.append("---")
    lines.append(f"*Generated from synthetic fixture. Figures are rounded for readability.*")
    lines.append("")

    with open(output_path, "w") as f:
        f.write("\n".join(lines))


def generate_chart(df: pd.DataFrame, output_path: str) -> None:
    """Generate a bar chart PNG of the synthetic test expenses."""
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar(df["label"], df["amount"], color=["#4C72B0", "#55A868", "#C44E52"])
    ax.set_title("Synthetic Test Expenses")
    ax.set_xlabel("Label")
    ax.set_ylabel("Amount")
    ax.axhline(y=df["amount"].mean(), color="gray", linestyle="--", label=f"Mean = {df['amount'].mean():.2f}")
    ax.legend()
    fig.tight_layout()
    fig.savefig(output_path, dpi=120)
    plt.close(fig)
