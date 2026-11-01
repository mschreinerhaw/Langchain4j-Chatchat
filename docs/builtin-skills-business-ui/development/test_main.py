"""main 模块的单元测试（标准库 unittest，直接导入真实实现）。"""

from __future__ import annotations

import io
import json
import os
import unittest
from contextlib import redirect_stdout
from unittest import mock

import main


class SummarizeTests(unittest.TestCase):
    """summarize 的正常与边界行为。"""

    def test_summarize_integers(self) -> None:
        self.assertEqual(main.summarize([1, 2, 3]), {"count": 3, "sum": 6})

    def test_summarize_mixed_numbers(self) -> None:
        self.assertEqual(main.summarize([1, 2.5, -0.5]), {"count": 3, "sum": 3.0})

    def test_summarize_single_value(self) -> None:
        self.assertEqual(main.summarize([42]), {"count": 1, "sum": 42})

    def test_summarize_negative_values(self) -> None:
        result = main.summarize([-1, -2, -3])
        self.assertEqual(result["count"], 3)
        self.assertEqual(result["sum"], -6)

    def test_summarize_empty_list(self) -> None:
        self.assertEqual(main.summarize([]), {"count": 0, "sum": 0})

    def test_rejects_non_list(self) -> None:
        for candidate in (None, 0, 1.5, "123", (1, 2), {1, 2}, {"a": 1}):
            with self.subTest(candidate=candidate):
                with self.assertRaises(main.InputError):
                    main.summarize(candidate)

    def test_rejects_bool_elements(self) -> None:
        for candidate in ([True], [False], [1, True], [0, False]):
            with self.subTest(candidate=candidate):
                with self.assertRaises(main.InputError):
                    main.summarize(candidate)

    def test_rejects_non_numeric_elements(self) -> None:
        for candidate in (["1"], [None], [[1]], [{"v": 1}], [1, "2"]):
            with self.subTest(candidate=candidate):
                with self.assertRaises(main.InputError):
                    main.summarize(candidate)

    def test_rejects_non_finite_elements(self) -> None:
        for candidate in ([float("nan")], [float("inf")], [float("-inf")], [1.0, float("nan")]):
            with self.subTest(candidate=candidate):
                with self.assertRaises(main.InputError):
                    main.summarize(candidate)

    def test_input_error_is_value_error(self) -> None:
        with self.assertRaises(ValueError):
            main.summarize("not-a-list")


class ExtractValuesTests(unittest.TestCase):
    """输入解析与校验。"""

    def test_extracts_values_field(self) -> None:
        self.assertEqual(main.extract_values('{"values": [1, 2, 3]}'), [1, 2, 3])

    def test_rejects_missing_input(self) -> None:
        with self.assertRaises(main.InputError):
            main.extract_values(None)

    def test_rejects_blank_input(self) -> None:
        with self.assertRaises(main.InputError):
            main.extract_values("   ")

    def test_rejects_invalid_json(self) -> None:
        with self.assertRaises(main.InputError):
            main.extract_values('{"values": [1, 2,')

    def test_rejects_non_object_json(self) -> None:
        with self.assertRaises(main.InputError):
            main.extract_values("[1, 2, 3]")

    def test_rejects_missing_values_key(self) -> None:
        with self.assertRaises(main.InputError):
            main.extract_values('{"other": 1}')


class RunTests(unittest.TestCase):
    """run 的退出码与结果结构。"""

    def test_success_payload(self) -> None:
        exit_code, payload = main.run('{"values": [1, 2, 3]}')
        self.assertEqual(exit_code, 0)
        self.assertEqual(payload, {"ok": True, "count": 3, "sum": 6})

    def test_empty_list_payload(self) -> None:
        exit_code, payload = main.run('{"values": []}')
        self.assertEqual(exit_code, 0)
        self.assertEqual(payload, {"ok": True, "count": 0, "sum": 0})

    def test_invalid_input_payload(self) -> None:
        exit_code, payload = main.run('{"values": [true]}')
        self.assertEqual(exit_code, 2)
        self.assertFalse(payload["ok"])
        self.assertIn("error", payload)


class MainEntryTests(unittest.TestCase):
    """main 入口的环境变量读取与 JSON 输出。"""

    def _invoke_main(self, raw: str | None) -> tuple[int, dict[str, object]]:
        buffer = io.StringIO()
        environment = {}
        if raw is not None:
            environment[main.INPUT_ENV_VAR] = raw
        with mock.patch.dict(os.environ, environment, clear=True):
            with redirect_stdout(buffer):
                exit_code = main.main()
        output = buffer.getvalue().strip()
        self.assertTrue(output, "main 必须输出内容")
        return exit_code, json.loads(output)

    def test_main_success(self) -> None:
        exit_code, payload = self._invoke_main('{"values": [1, 2, 3.5]}')
        self.assertEqual(exit_code, 0)
        self.assertEqual(payload, {"ok": True, "count": 3, "sum": 6.5})

    def test_main_empty_list(self) -> None:
        exit_code, payload = self._invoke_main('{"values": []}')
        self.assertEqual(exit_code, 0)
        self.assertEqual(payload, {"ok": True, "count": 0, "sum": 0})

    def test_main_missing_environment_variable(self) -> None:
        exit_code, payload = self._invoke_main(None)
        self.assertEqual(exit_code, 2)
        self.assertFalse(payload["ok"])

    def test_main_rejects_non_list_values(self) -> None:
        exit_code, payload = self._invoke_main('{"values": "1,2,3"}')
        self.assertEqual(exit_code, 2)
        self.assertFalse(payload["ok"])

    def test_main_rejects_non_finite_value(self) -> None:
        exit_code, payload = self._invoke_main('{"values": [1, NaN]}')
        self.assertEqual(exit_code, 2)
        self.assertFalse(payload["ok"])


if __name__ == "__main__":
    unittest.main()
