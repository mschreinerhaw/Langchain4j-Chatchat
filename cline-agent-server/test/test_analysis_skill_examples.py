import csv
import importlib.util
import json
import os
from pathlib import Path
import re
from tempfile import TemporaryDirectory
import unittest


REFERENCES = Path(__file__).resolve().parents[2] / "coding-agent-node/skills/python-data-analysis/references"


def example(file_name, name):
    text = (REFERENCES / file_name).read_text(encoding="utf-8")
    source = next(block for block in re.findall(r"```python\n(.*?)```", text, re.S)
                  if f"def {name}(" in block)
    namespace = {}
    exec(source, namespace)
    return namespace[name]


class AnalysisSkillExamplesTest(unittest.TestCase):
    def test_entire_snapshot_and_late_row(self):
        inspect_jsonl = example("data-methods.md", "inspect_jsonl")
        sizes = [int(n) for n in os.getenv("CLINE_TEST_ANALYSIS_ROWS", "10000").split(",")]
        for size in sizes:
            with self.subTest(size=size), TemporaryDirectory() as directory:
                path = Path(directory) / "source.jsonl"
                with path.open("wb") as target:
                    batches, rest = divmod(size - 1, 10000)
                    for _ in range(batches):
                        target.write(b'{"n":1}\n' * 10000)
                    target.write(b'{"n":1}\n' * rest)
                    target.write(b'{"n":null,"late":true}\n')
                result = inspect_jsonl(path, size, ["n", "late"])
                self.assertEqual(result["snapshotRows"], size)
                self.assertEqual(result["fields"]["late"], {"present": 1, "null": 0, "missing": size - 1})
                self.assertEqual(result["fields"]["n"]["null"], 1)

    def test_empty_malformed_and_mismatched_snapshot(self):
        inspect_jsonl = example("data-methods.md", "inspect_jsonl")
        with TemporaryDirectory() as directory:
            path = Path(directory) / "source.jsonl"
            path.touch()
            self.assertEqual(inspect_jsonl(path, 0, ["x"])["snapshotRows"], 0)
            with self.assertRaisesRegex(ValueError, "row count differs"):
                inspect_jsonl(path, 1, ["x"])
            path.write_text('{"x":1}\ninvalid', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "line 2"):
                inspect_jsonl(path, 2, ["x"])

    def test_complete_long_text_with_locations(self):
        matching_evidence = example("log-analysis.md", "matching_evidence")
        text = "ERROR start\n" + "x" * 50000 + "\nERROR middle\n" + "y" * 50000 + "\nERROR end"
        locations = list(matching_evidence(text, re.compile(r"ERROR \w+"), "snapshot:demo", "row-1"))
        self.assertEqual([event["line"] for event in locations], [1, 3, 5])
        self.assertEqual([text[event["characterStart"]:event["characterEnd"]] for event in locations],
                         ["ERROR start", "ERROR middle", "ERROR end"])
        self.assertEqual({event["source"] for event in locations}, {"snapshot:demo"})

    @unittest.skipUnless(importlib.util.find_spec("pyarrow") and importlib.util.find_spec("openpyxl"),
                         "Optional Sandbox format libraries are required")
    def test_full_calculation_across_sandbox_formats(self):
        from openpyxl import Workbook, load_workbook
        import pyarrow as pa
        import pyarrow.parquet as pq

        sizes = (100, 10_000)
        for size in sizes:
            with self.subTest(size=size), TemporaryDirectory() as directory:
                root = Path(directory)
                last = 1_000_000
                expected = (size, size * (size - 1) // 2 + last, last)

                def values():
                    yield from range(size)
                    yield last

                def calculate(records):
                    count = total = largest = 0
                    for value in records:
                        if value > 0:
                            count += 1
                            total += value
                            largest = max(largest, value)
                    return count, total, largest

                with (root / "source.csv").open("w", newline="", encoding="utf-8") as stream:
                    writer = csv.writer(stream)
                    writer.writerow(["amount"])
                    writer.writerows((value,) for value in values())
                with (root / "source.csv").open(newline="", encoding="utf-8") as stream:
                    self.assertEqual(calculate(int(row["amount"]) for row in csv.DictReader(stream)), expected)

                with (root / "source.jsonl").open("w", encoding="utf-8") as stream:
                    for value in values():
                        stream.write(json.dumps({"amount": value}) + "\n")
                with (root / "source.jsonl").open(encoding="utf-8") as stream:
                    self.assertEqual(calculate(json.loads(row)["amount"] for row in stream), expected)

                pq.write_table(pa.table({"amount": list(values())}), root / "source.parquet", row_group_size=128)
                parquet = pq.ParquetFile(root / "source.parquet")
                try:
                    self.assertEqual(calculate(value for batch in parquet.iter_batches(batch_size=128, columns=["amount"])
                                               for value in batch.column(0).to_pylist()), expected)
                finally:
                    parquet.close()

                workbook = Workbook(write_only=True)
                sheet = workbook.create_sheet()
                sheet.append(["amount"])
                for value in values():
                    sheet.append([value])
                workbook.save(root / "source.xlsx")
                workbook = load_workbook(root / "source.xlsx", read_only=True, data_only=True)
                try:
                    rows = workbook.active.iter_rows(values_only=True)
                    next(rows)
                    self.assertEqual(calculate(row[0] for row in rows), expected)
                finally:
                    workbook.close()


if __name__ == "__main__":
    unittest.main()
