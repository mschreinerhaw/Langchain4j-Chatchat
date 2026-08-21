import { describe, expect, it } from "vitest";
import { pythonCompletionItems } from "./pythonCompletions";

describe("Python editor completions", () => {
  it("includes language, snippets, document symbols and available data files", () => {
    const items = pythonCompletionItems(
      "import pandas as pd\ndef analyze(data):\n    result = data",
      "res",
      [{ fileName: "sales.csv", fileType: "CSV", status: "AVAILABLE", pythonPath: "/data/sales.csv" }]
    );
    expect(items).toEqual(expect.arrayContaining([
      expect.objectContaining({ label: "analyze", category: "function" }),
      expect.objectContaining({ label: "result", category: "variable" }),
      expect.objectContaining({ label: "读取 CSV", category: "snippet" }),
      expect.objectContaining({ label: "len", category: "function" }),
      expect.objectContaining({ label: "sales.csv", insertText: '"/data/sales.csv"' })
    ]));
  });

  it("returns contextual members after a dot", () => {
    const pandasItems = pythonCompletionItems("import pandas as pd", "value = pd.read");
    expect(pandasItems).toEqual(expect.arrayContaining([
      expect.objectContaining({ label: "read_csv" }),
      expect.objectContaining({ label: "read_parquet" }),
      expect.objectContaining({ label: "read_orc" })
    ]));
    expect(pandasItems.some((entry) => entry.label === "while")).toBe(false);

    const frameItems = pythonCompletionItems("df = pd.read_csv(path)", "df.");
    expect(frameItems).toEqual(expect.arrayContaining([
      expect.objectContaining({ label: "groupby" }),
      expect.objectContaining({ label: "to_dict" })
    ]));
  });
});
