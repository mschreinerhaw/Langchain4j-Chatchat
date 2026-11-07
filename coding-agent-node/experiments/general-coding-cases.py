"""Fixed generic A-E fixtures for three-group acceptance. Does not call models or repair code.

F reuses E-key with an independently recorded Worker interruption. G uses the same
task/authorization infrastructure and separate negative probes. Holdouts are never
fed back to the Agent or charged to its original budget.
"""
import hashlib
import json

COMMAND = "python -m unittest discover -s tests -v"


def case(name, instruction, files, holdout, preserve=()):
    return {
        "id": name,
        "instruction": instruction,
        "files": files,
        "readOnlyPaths": [p for p in files if p.startswith("tests/")],
        "acceptanceCommand": COMMAND,
        "initialFileHashes": {p: hashlib.sha256(s.encode()).hexdigest() for p, s in sorted(files.items())},
        "independentHoldout": holdout,
        "preserveFunctions": list(preserve),
    }


def cases():
    result = [case("A", "创建多文件 Python 项目 textkit，包含 textkit/__init__.py、textkit/normalize.py、textkit/cli.py 和 tests/test_normalize.py。normalize.py 提供 slug(text)：按小写 ASCII 字母和数字分词，以单个连字符连接，空输入返回空串。cli.py 可用 python -m textkit.cli TEXT 打印 slug。编写至少六项有意义的 unittest，运行项目测试并自行修复实际错误。交付源码与测试结果。", {},
                   "from textkit.normalize import slug\nassert slug(' Hello, WORLD! 42 ') == 'hello-world-42'\nassert slug('') == ''\nassert slug('a__b--c') == 'a-b-c'\nassert slug('中文 X') == 'x'\nimport textkit.cli\n")]
    tests = "import unittest\nfrom calc import add\nclass AdditionTests(unittest.TestCase):\n    def test_positive(self): self.assertEqual(add(2,3),5)\n    def test_negative(self): self.assertEqual(add(-2,-3),-5)\n    def test_identity(self): self.assertEqual(add(0,7),7)\n"
    result.append(case("B", "修复现有 Python 项目的 add(a,b)，使其返回两数之和。保留已有测试，自主定位、编辑和运行测试，交付源码与真实执行证据。", {"calc.py": "def add(a, b):\n    return a - b\n", "tests/test_calc.py": tests}, "from calc import add\nassert add(17,-4)==13\nassert add(-11,6)==-5\nassert add(1.25,2.5)==3.75\n"))
    helpers = "\n".join(f"def helper_{i:03d}(value):\n    return value + {i}\n" for i in range(180))
    source = "def normalize_key(text):\n    return text.strip().upper()\n\ndef parse_count(text):\n    return int(text.strip())\n\n" + helpers
    tests = "import unittest\nfrom large_module import normalize_key, parse_count\nclass ExistingTests(unittest.TestCase):\n    def test_key(self): self.assertEqual(normalize_key('  Some_Key '),'some_key')\n    def test_count(self): self.assertEqual(parse_count(' 1,024 '),1024)\n    def test_negative(self): self.assertEqual(parse_count('-2,000'),-2000)\n"
    holdout = "import large_module as m\nassert m.normalize_key(' AbC ')=='abc'\nassert m.parse_count('0')==0\nassert m.parse_count('9,876,543')==9876543\n" + "\n".join(f"assert m.helper_{i:03d}(7)=={7+i}" for i in range(180)) + "\n"
    result.append(case("C", "修复 large_module.py 的 normalize_key 与 parse_count：前者去除首尾空格并转小写；后者接受带千位逗号的有符号整数。仅修改所需函数，保留其余 180 个 helper 的源码不变。运行已有测试，自主处理错误并交付。", {"large_module.py": source, "tests/test_large.py": tests}, holdout, [f"helper_{i:03d}" for i in range(180)]))
    tests = "import unittest\nfrom stats import average\nfrom helpers import total\nclass CompatibilityTests(unittest.TestCase):\n    def test_mean(self): self.assertEqual(average([2,4,6]),4)\n    def test_empty(self): self.assertIsNone(average([]))\n    def test_total(self): self.assertEqual(total([-1,3]),2)\n"
    files = {"helpers.py": "def total(values):\n    return sum(values)\n\ndef mean(values):\n    return total(values) / len(values) if values else None\n", "stats.py": "from helpers import mean\n\ndef average(values):\n    return mean(values)\n", "tests/test_stats.py": tests}
    result.append(case("D", "将 helpers.py 的 total 和 mean 实现迁移到 core/reducers.py，建立 Python 包；helpers.py 保留兼容导入；stats.py 改用新模块。保持现有对外行为与测试不变，运行测试并交付多文件重构。", files, "from core.reducers import total, mean\nfrom helpers import total as old_total, mean as old_mean\nfrom stats import average\nassert old_total is total and old_mean is mean\nassert total([1,2,3])==6\nassert mean([]) is None\nassert average([1,2,3])==2\n"))
    variants = {
        "syntax": ("def transform(value)\n    return value * 2\n", "self.assertEqual(transform(7),14)", "from transform import transform\nassert transform(-4)==-8\nassert transform(0)==0\n"),
        "key": ("def transform(value):\n    return value['count'] * 2\n", "self.assertEqual(transform({'quantity':7}),14)", "from transform import transform\nassert transform({'quantity':-4})==-8\nassert transform({'quantity':0})==0\n"),
        "type": ("from decimal import Decimal\n\ndef transform(value):\n    return value + 0.5\n", "self.assertEqual(transform(Decimal('7')),Decimal('7.5'))", "from decimal import Decimal\nfrom transform import transform\nassert transform(Decimal('-4.25'))==Decimal('-3.75')\nassert transform(Decimal('0'))==Decimal('0.5')\nassert isinstance(transform(Decimal('1.234567890123456789')),Decimal)\n"),
        "name": ("def transform(value):\n    return item * 2\n", "self.assertEqual(transform(7),14)", "from transform import transform\nassert transform(-4)==-8\nassert transform(0)==0\n"),
    }
    for kind, (source, assertion, holdout) in variants.items():
        tests = "import unittest\nfrom decimal import Decimal\nfrom transform import transform\nclass ExistingTests(unittest.TestCase):\n    def test_transform(self): " + assertion + "\n"
        result.append(case("E-" + kind, "修复当前 transform.py。先执行现有测试以取得真实错误，再由你判断并修改源码、重新测试。行为由不可修改的 tests/test_transform.py 定义；保持数据类型正确，交付修复与执行证据。", {"transform.py": source, "tests/test_transform.py": tests}, holdout))
    return result


if __name__ == "__main__":
    print(json.dumps({"schemaVersion": "native-coding.generic-cases.v1", "cases": cases()}, ensure_ascii=False, indent=2))
