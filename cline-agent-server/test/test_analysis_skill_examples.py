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


if __name__ == "__main__":
    unittest.main()
