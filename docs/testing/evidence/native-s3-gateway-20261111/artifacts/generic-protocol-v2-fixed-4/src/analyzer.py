"""Analyzer module: reads CSV fixture and computes statistics."""

import pandas as pd
from pathlib import Path


def load_expenses(csv_path: str) -> pd.DataFrame:
    """Load the expenses CSV into a DataFrame."""
    df = pd.read_csv(csv_path)
    return df


def compute_statistics(df: pd.DataFrame) -> dict:
    """Compute rows, total, mean from the expenses DataFrame.

    Returns a dict with keys: rows, total, mean, dataStatus, summary_line.
    """
    rows = int(len(df))
    total = float(df["amount"].sum())
    mean = float(df["amount"].mean())

    # REPAIRED: convert int to str before concatenation
    summary_line = "Processed " + str(rows) + " expense rows"

    return {
        "rows": rows,
        "total": total,
        "mean": mean,
        "dataStatus": "synthetic test fixture — not customer/business data",
        "summary_line": summary_line,
    }
