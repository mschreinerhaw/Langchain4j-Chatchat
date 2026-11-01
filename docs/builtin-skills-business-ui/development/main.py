"""汇总数值列表的 count 与 sum。

程序从环境变量 ``CHATCHAT_INPUT_JSON`` 读取 JSON 对象（其中 ``values`` 字段应为数字列表），
校验后把统计结果以 JSON 形式输出到标准输出。

退出码：
    0 -- 成功；
    2 -- 输入不合法（缺失、非 JSON、非对象、values 非法等）。
"""

from __future__ import annotations

import json
import logging
import math
import os
from typing import Any, TypedDict

INPUT_ENV_VAR = "CHATCHAT_INPUT_JSON"

#: 允许的数值类型；bool 是 int 的子类，需在运行时单独排除。
Number = int | float

logger = logging.getLogger(__name__)


class InputError(ValueError):
    """输入数据不合法：非列表、布尔值、非数值或非有限数。"""


class Summary(TypedDict):
    """汇总结果结构。"""

    count: int
    sum: Number


def _validate_number(value: Any, index: int) -> Number:
    """校验单个元素并返回其数值。

    Args:
        value: 待校验的列表元素。
        index: 元素在列表中的下标，仅用于错误信息。

    Returns:
        原样返回的 ``int`` 或 ``float``。

    Raises:
        InputError: 元素是布尔值、非数值或非有限数。
    """
    if isinstance(value, bool):
        raise InputError(f"values[{index}] 不能是布尔值: {value!r}")
    if not isinstance(value, (int, float)):
        raise InputError(
            f"values[{index}] 必须是数值，实际类型为 {type(value).__name__}"
        )
    if isinstance(value, float) and not math.isfinite(value):
        raise InputError(f"values[{index}] 必须是有限数，实际为 {value!r}")
    return value


def summarize(values: object) -> Summary:
    """汇总数字列表。

    Args:
        values: 期望为 ``list[int | float]``；空列表合法。

    Returns:
        含 ``count``（元素个数）与 ``sum``（元素之和）的字典。
        全部为整数时 ``sum`` 为 ``int``，否则为 ``float``。

    Raises:
        InputError: ``values`` 不是列表，或包含布尔值、非数值、非有限数。
    """
    if not isinstance(values, list):
        raise InputError(f"values 必须是列表，实际类型为 {type(values).__name__}")

    numbers = [_validate_number(item, index) for index, item in enumerate(values)]
    total: Number = sum(numbers)
    return {"count": len(numbers), "sum": total}


def extract_values(raw: str | None) -> object:
    """从原始 JSON 文本中取出 ``values`` 字段（不做列表校验）。

    Args:
        raw: 环境变量 ``CHATCHAT_INPUT_JSON`` 的原始内容。

    Returns:
        ``values`` 字段的原始值，交由 :func:`summarize` 校验。

    Raises:
        InputError: 内容缺失、不是合法 JSON、不是 JSON 对象或缺少 ``values`` 字段。
    """
    if raw is None:
        raise InputError(f"缺少环境变量 {INPUT_ENV_VAR}")

    text = raw.strip()
    if not text:
        raise InputError(f"环境变量 {INPUT_ENV_VAR} 为空")

    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise InputError(f"环境变量 {INPUT_ENV_VAR} 不是合法 JSON: {exc.msg}") from exc

    if not isinstance(payload, dict):
        raise InputError(
            f"环境变量 {INPUT_ENV_VAR} 必须是 JSON 对象，实际为 {type(payload).__name__}"
        )
    if "values" not in payload:
        raise InputError(f"环境变量 {INPUT_ENV_VAR} 缺少 values 字段")
    return payload["values"]


def run(raw: str | None) -> tuple[int, dict[str, Any]]:
    """执行一次完整流程并返回退出码与待输出的结果字典。

    Args:
        raw: 环境变量 ``CHATCHAT_INPUT_JSON`` 的原始内容。

    Returns:
        ``(exit_code, payload)``：成功时 payload 含 ``ok``/``count``/``sum``，
        失败时 payload 含 ``ok=False`` 与 ``error`` 说明。
    """
    try:
        summary = summarize(extract_values(raw))
    except InputError as exc:
        logger.debug("输入校验失败: %s", exc)
        return 2, {"ok": False, "error": str(exc)}

    return 0, {"ok": True, "count": summary["count"], "sum": summary["sum"]}


def main() -> int:
    """命令行入口：读取环境变量、输出 JSON 并返回退出码。"""
    exit_code, payload = run(os.environ.get(INPUT_ENV_VAR))
    print(json.dumps(payload, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
