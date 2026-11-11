"""analyze.py - Core analysis module for synthetic test expense data."""

import json
import pandas as pd


DATA_STATUS = "synthetic_test_fixture"


def load_expenses(csv_path: str) -> pd.DataFrame:
    """Load the CSV fixture into a DataFrame."""
    df = pd.read_csv(csv_path)
    return df


def compute_statistics(df: pd.DataFrame) -> dict:
    """Compute rows, total, mean from the expense DataFrame."""
    rows = len(df)
    total = float(df["amount"].sum())
    mean = float(df["amount"].mean())
    return {
        "rows": rows,
        "total": total,
        "mean": mean,
        "dataStatus": DATA_STATUS,
    }


def build_summary_line(stats: dict) -> str:
    """Build a human-readable summary line from statistics."""
    summary = (
        "Rows: " + str(stats["rows"])
        + " | Total: " + str(stats["total"])
        + " | Mean: " + str(stats["mean"])
    )
    return summary


def save_result_json(stats: dict, output_path: str) -> None:
    """Persist the structured JSON result."""
    with open(output_path, "w") as f:
        json.dump(stats, f, indent=2)


if __name__ == "__main__":
    df = load_expenses("../data/test_expenses.csv")
    stats = compute_statistics(df)
    print(build_summary_line(stats))
    save_result_json(stats, "../output/result.json")
