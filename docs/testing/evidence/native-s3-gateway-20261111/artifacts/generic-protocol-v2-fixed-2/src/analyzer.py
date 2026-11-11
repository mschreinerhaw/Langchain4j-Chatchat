"""analyzer.py — Core analysis module for synthetic expense data.

Reads a CSV with pandas and computes rows / total / mean.
"""

import pandas as pd


def load_expenses(csv_path: str) -> pd.DataFrame:
    """Load the expense CSV and return a DataFrame."""
    df = pd.read_csv(csv_path)
    return df


def compute_statistics(df: pd.DataFrame) -> dict:
    """Return rows, total, mean for the 'amount' column.

    NOTE (v0 — buggy): the summary line concatenates a str with an int,
    which will raise a TypeError at runtime.  This is the intentional
    defect we will observe and then repair.
    """
    rows = len(df)
    total = float(df["amount"].sum())
    mean = total / rows if rows > 0 else 0.0

    # --- repaired: use f-string to avoid str+int TypeError ---
    summary = f"Processed {rows} expense rows."
    # ---------------------------------------------------------

    return {
        "rows": rows,
        "total": total,
        "mean": mean,
        "summary": summary,
    }
