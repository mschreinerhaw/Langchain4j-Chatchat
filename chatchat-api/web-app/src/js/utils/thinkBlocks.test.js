import { describe, expect, it } from "vitest";
import { splitThinkBlocks } from "./thinkBlocks.js";

describe("splitThinkBlocks", () => {
  it("keeps the answer outside multiple thinking sections", () => {
    expect(splitThinkBlocks("前文<think>分析一</think>正文<think>分析二</think>结论"))
      .toEqual([
        { type: "text", content: "前文" },
        { type: "thinking", content: "分析一" },
        { type: "text", content: "正文" },
        { type: "thinking", content: "分析二" },
        { type: "text", content: "结论" }
      ]);
  });

  it("collapses an unfinished streaming thought and hides incomplete opening tags", () => {
    expect(splitThinkBlocks("<think>正在分析"))
      .toEqual([{ type: "thinking", content: "正在分析" }]);
    expect(splitThinkBlocks("答案<thi"))
      .toEqual([{ type: "text", content: "答案" }, { type: "thinking", content: "" }]);
  });

  it("does not leave a stray closing marker in the visible answer", () => {
    expect(splitThinkBlocks("</think>\n# 结果"))
      .toEqual([{ type: "text", content: "\n# 结果" }]);
  });

  it("does not alter ordinary markdown", () => {
    expect(splitThinkBlocks("\n# 标题\n内容\n"))
      .toEqual([{ type: "text", content: "\n# 标题\n内容\n" }]);
  });
});
