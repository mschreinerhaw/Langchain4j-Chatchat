import assert from "node:assert/strict";
import test from "node:test";

import { selectCompleteMessageContent } from "./messageContentSelection.js";

test("selects full content when a legacy structured answer is its truncated prefix", () => {
  const prefix = "# 客户全景分析报告\n\n" + "分析。".repeat(1000);
  const full = prefix + "\n\n## 后续章节\n\n" + "完整记录。".repeat(1000);

  assert.equal(selectCompleteMessageContent(prefix, full), full);
});

test("keeps an intentional structured answer when content is divergent", () => {
  assert.equal(
    selectCompleteMessageContent("面向用户的清理后答案", "内部协议与调试正文"),
    "面向用户的清理后答案"
  );
});

test("selects full content after the persistence layer adds a truncation marker", () => {
  const prefix = "持久化回答。".repeat(3000);
  const structured = `${prefix}\n...[nested value truncated; originalBytes=48000]`;
  const full = `${prefix}\n\n## 完整后续章节\n\n${"明细。".repeat(3000)}`;

  assert.equal(selectCompleteMessageContent(structured, full), full);
});
