"""Analyzer module: loads CSV data and computes statistics."""

import pandas as pd
from pathlib import Path


def load_expenses(csv_path: str) -> pd.DataFrame:
    """Load expenses from a CSV file into a DataFrame."""
    df = pd.read_csv(csv_path)
    return df


def compute_statistics(df: pd.DataFrame) -> dict:
    """Compute row count, total, and mean of the amount column.

    Returns a dict with keys: rows, total, mean, dataStatus.
    """
    rows = len(df)
    total = df["amount"].sum()
    mean = total / rows
    return {
        "rows": rows,
        "total": total,
        "mean": mean,
        "dataStatus": "synthetic_test_fixture",
    }
