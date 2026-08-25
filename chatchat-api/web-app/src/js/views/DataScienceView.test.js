import { readFileSync } from "node:fs";
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

  it("publishes with local progress and refreshes without rebuilding the workbench", () => {
    const script = readFileSync(new URL("./DataScienceView.js", import.meta.url), "utf8");
    const view = readFileSync(new URL("../../views/DataScienceView.vue", import.meta.url), "utf8");
    const publishMethod = script.slice(
      script.indexOf("async publish()"),
      script.indexOf("async openPublishDialog()")
    );

    expect(publishMethod).toContain("this.publishBusy = true");
    expect(publishMethod).toContain("await this.load(true)");
    expect(publishMethod).not.toContain("await this.load();");
    expect(view).toContain('class="publish-progress"');
    expect(view).toContain(':disabled="publishBusy"');
  });
});
