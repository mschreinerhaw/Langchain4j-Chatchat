import { describe, expect, it } from "vitest";
import {
  calculateBottomPanelMaximum,
  formatPythonSource,
  parsePythonExecutionParameters
} from "../utils/pythonWorkbench";

describe("Python workbench helpers", () => {
  it("accepts only JSON objects as execution parameters", () => {
    expect(parsePythonExecutionParameters('{"limit":10,"enabled":true}')).toEqual({
      limit: 10,
      enabled: true
    });
    expect(() => parsePythonExecutionParameters("[1,2]")).toThrow("JSON 对象");
    expect(() => parsePythonExecutionParameters("null")).toThrow("JSON 对象");
  });

  it("normalizes tabs, trailing spaces and excessive blank lines", () => {
    expect(formatPythonSource("def main():  \r\n\treturn 1\r\n\r\n\r\n\r\n")).toBe(
      "def main():\n    return 1\n"
    );
  });

  it("keeps the bottom panel resizable while reserving editor space", () => {
    expect(calculateBottomPanelMaximum(532)).toBe(311);
    expect(calculateBottomPanelMaximum(250)).toBe(100);
    expect(calculateBottomPanelMaximum(900)).toBe(520);
  });
});
