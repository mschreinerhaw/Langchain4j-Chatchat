const KEYWORDS = [
  "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
  "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if",
  "import", "in", "is", "lambda", "match", "None", "nonlocal", "not", "or", "pass",
  "raise", "return", "True", "try", "while", "with", "yield"
];

const BUILTINS = [
  ["print", "print(${1:value})", "输出内容"],
  ["len", "len(${1:object})", "返回对象长度"],
  ["range", "range(${1:stop})", "创建整数序列"],
  ["enumerate", "enumerate(${1:iterable})", "同时遍历索引和值"],
  ["zip", "zip(${1:iterables})", "并行迭代多个对象"],
  ["sum", "sum(${1:iterable})", "计算合计值"],
  ["min", "min(${1:iterable})", "返回最小值"],
  ["max", "max(${1:iterable})", "返回最大值"],
  ["sorted", "sorted(${1:iterable}, key=${2:None}, reverse=${3:False})", "返回排序结果"],
  ["open", 'open(${1:path}, "${2:r}", encoding="utf-8")', "打开文件"],
  ["isinstance", "isinstance(${1:object}, ${2:type})", "检查对象类型"],
  ["str", "str(${1:object})", "转换为字符串"],
  ["int", "int(${1:value})", "转换为整数"],
  ["float", "float(${1:value})", "转换为浮点数"],
  ["list", "list(${1:iterable})", "创建列表"],
  ["dict", "dict(${1:items})", "创建字典"],
  ["set", "set(${1:iterable})", "创建集合"],
  ["tuple", "tuple(${1:iterable})", "创建元组"],
  ["any", "any(${1:iterable})", "任一元素为真"],
  ["all", "all(${1:iterable})", "所有元素为真"]
];

const SNIPPETS = [
  ["读取运行参数", 'params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))', "读取 Agent 传入的 JSON 对象"],
  ["输出 JSON 结果", "print(json.dumps(${1:result}, ensure_ascii=False))", "向 Agent 输出 UTF-8 JSON"],
  ["main 函数", "def main(${1:data}):\n    ${2:result} = {}\n    return ${2:result}", "创建分析模板入口"],
  ["读取 CSV", 'df = pd.read_csv(${1:source_file}, encoding="${2:utf-8}")', "读取动态 FILE 参数对应的 CSV"],
  ["读取 Excel", "df = pd.read_excel(${1:source_file}, sheet_name=${2:0})", "读取动态 FILE 参数对应的 XLS/XLSX"],
  ["读取 JSON", "df = pd.read_json(${1:source_file})", "读取动态 FILE 参数对应的 JSON"],
  ["读取 Parquet", "df = pd.read_parquet(${1:source_file})", "读取动态 FILE 参数对应的 Parquet"],
  ["读取 ORC", "df = pd.read_orc(${1:source_file})", "读取动态 FILE 参数对应的 ORC"],
  ["异常处理", "try:\n    ${1:pass}\nexcept ${2:Exception} as exc:\n    ${3:raise}", "创建 try/except 结构"],
  ["遍历数据", "for ${1:item} in ${2:items}:\n    ${3:pass}", "创建 for 循环"]
];

const MEMBERS = {
  pd: [
    ["read_csv", "read_csv(${1:file_path})", "读取 CSV 文件"],
    ["read_excel", "read_excel(${1:file_path}, sheet_name=${2:0})", "读取 Excel 文件"],
    ["read_json", "read_json(${1:file_path})", "读取 JSON 文件"],
    ["read_parquet", "read_parquet(${1:file_path})", "读取 Parquet 文件"],
    ["read_orc", "read_orc(${1:file_path})", "读取 ORC 文件"],
    ["DataFrame", "DataFrame(${1:data})", "创建 DataFrame"],
    ["concat", "concat(${1:objects}, ignore_index=${2:True})", "合并多个对象"],
    ["merge", "merge(${1:left}, ${2:right}, on=${3:key})", "合并 DataFrame"]
  ],
  json: [
    ["loads", "loads(${1:text})", "解析 JSON 字符串"],
    ["dumps", "dumps(${1:object}, ensure_ascii=False)", "序列化为 JSON 字符串"],
    ["load", "load(${1:file})", "从文件解析 JSON"],
    ["dump", "dump(${1:object}, ${2:file}, ensure_ascii=False)", "将 JSON 写入文件"]
  ],
  os: [
    ["environ", "environ", "进程环境变量"],
    ["getenv", 'getenv("${1:name}", ${2:None})', "读取环境变量"],
    ["path", "path", "路径处理模块"],
    ["listdir", "listdir(${1:path})", "列出目录内容"]
  ],
  df: [
    ["head", "head(${1:5})", "查看前几行"],
    ["info", "info()", "查看数据结构"],
    ["describe", "describe(include=${1:'all'})", "生成描述性统计"],
    ["groupby", "groupby(${1:columns})", "按列分组"],
    ["merge", "merge(${1:right}, on=${2:key}, how=\"${3:left}\")", "合并数据"],
    ["fillna", "fillna(${1:value})", "填充缺失值"],
    ["dropna", "dropna()", "删除缺失值"],
    ["sort_values", "sort_values(${1:by}, ascending=${2:True})", "按值排序"],
    ["to_dict", 'to_dict(orient="${1:records}")', "转换为字典"],
    ["to_json", 'to_json(orient="${1:records}", force_ascii=False)', "转换为 JSON"]
  ]
};

function item(label, insertText = label, detail = "", category = "keyword", snippet = false) {
  return { label, insertText, detail, category, snippet };
}

function documentSymbols(source) {
  const symbols = [];
  const add = (label, category, detail) => {
    if (label && !symbols.some((entry) => entry.label === label)) symbols.push(item(label, label, detail, category));
  };
  for (const line of String(source || "").split(/\r?\n/)) {
    let match = line.match(/^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(/);
    if (match) add(match[1], "function", "当前脚本中定义的函数");
    match = line.match(/^\s*class\s+([A-Za-z_]\w*)\b/);
    if (match) add(match[1], "class", "当前脚本中定义的类");
    match = line.match(/^\s*([A-Za-z_]\w*)\s*=(?!=)/);
    if (match) add(match[1], "variable", "当前脚本中定义的变量");
    match = line.match(/^\s*import\s+[\w.]+(?:\s+as\s+([A-Za-z_]\w*))?/);
    if (match) add(match[1] || line.trim().split(/[.\s]/)[1], "module", "当前脚本导入的模块");
    match = line.match(/^\s*from\s+[\w.]+\s+import\s+([A-Za-z_]\w*)/);
    if (match) add(match[1], "module", "当前脚本导入的名称");
  }
  return symbols;
}

export function pythonCompletionItems(source, linePrefix = "", dataFiles = []) {
  const member = String(linePrefix).match(/([A-Za-z_]\w*)\.\w*$/)?.[1];
  if (member) {
    const key = MEMBERS[member] ? member : /^df|dataframe$/i.test(member) ? "df" : "";
    return (MEMBERS[key] || []).map(([label, insertText, detail]) =>
      item(label, insertText, detail, "method", true)
    );
  }

  const files = (dataFiles || [])
    .filter((file) => file?.status === "AVAILABLE" && file.pythonPath)
    .map((file) => item(file.fileName, JSON.stringify(file.pythonPath), `分析数据文件 · ${file.fileType || "FILE"}`, "file"));
  return [
    ...documentSymbols(source),
    ...files,
    ...SNIPPETS.map(([label, insertText, detail]) => item(label, insertText, detail, "snippet", true)),
    ...BUILTINS.map(([label, insertText, detail]) => item(label, insertText, detail, "function", true)),
    ...KEYWORDS.map((label) => item(label, label, "Python 关键字", "keyword"))
  ];
}
