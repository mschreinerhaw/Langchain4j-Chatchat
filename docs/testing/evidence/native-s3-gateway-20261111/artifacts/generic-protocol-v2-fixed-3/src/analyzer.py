"""
analyzer.py - Expense data analysis module
Loads CSV data and computes statistics.
"""
import pandas as pd


def load_expenses(csv_path: str) -> pd.DataFrame:
    """Load expenses from CSV file."""
    df = pd.read_csv(csv_path)
    return df


def compute_statistics(df: pd.DataFrame) -> dict:
    """
    Compute statistics for the expense data.
    Returns dict with rows, total, mean, and indexed records.
    """
    rows = len(df)
    total = float(df["amount"].sum())
    mean = total / rows if rows > 0 else 0.0

    # Build indexed records list
    records = []
    for i, row in df.iterrows():
        # FIXED: convert int index to string for concatenation
        record_id = "expense_" + str(i)
        records.append({
            "id": record_id,
            "label": str(row["label"]),
            "amount": float(row["amount"])
        })

    return {
        "rows": rows,
        "total": total,
        "mean": mean,
        "records": records
    }
