import assert from "node:assert/strict";
import test from "node:test";

import {
  boundMessagesForPersistence,
  boundQuestionForPersistence,
  compactAnalysisTreeForPersistence,
  utf8Size
} from "./conversationPersistenceBudget.js";

test("20MB tool traces and message content cannot cross the history persistence boundary", () => {
  const huge = "x".repeat(20_054_016);
  const messages = boundMessagesForPersistence([{
    id: "message-1",
    role: "assistant",
    content: huge,
    traces: [{ output: huge }],
    steps: [{ observation: huge }],
    sources: [{ content: huge }],
    evidencePremises: [{ raw: huge }]
  }]);
  const payload = JSON.stringify({
    question: boundQuestionForPersistence(huge),
    messages,
    analysisTree: compactAnalysisTreeForPersistence({ raw: huge })
  });

  assert.equal(messages.length, 1);
  assert.match(messages[0].content, /message content truncated/);
  assert.ok(utf8Size(payload) < 1_000_000, `payload must be safely below the 2MB HTTP gate, got ${utf8Size(payload)}`);
  assert.ok(!payload.includes(huge));
});

test("conversation budget retains newest messages and marks omitted history", () => {
  const messages = Array.from({ length: 30 }, (_, index) => ({
    id: `message-${index}`,
    role: index % 2 ? "assistant" : "user",
    content: `${index}:` + "z".repeat(100_000)
  }));

  const bounded = boundMessagesForPersistence(messages);

  assert.ok(utf8Size(JSON.stringify(bounded)) <= 1_200 * 1024 + 1024);
  assert.equal(bounded.at(-1).id, "message-29");
  assert.equal(bounded[0].persistenceHistoryTruncated, true);
  assert.ok(bounded[0].omittedOlderMessages > 0);
});

test("UTF-8 multibyte content is budgeted in bytes rather than JavaScript characters", () => {
  const chinese = "极端行情与公告".repeat(500_000);
  const payload = JSON.stringify({
    question: boundQuestionForPersistence(chinese),
    messages: boundMessagesForPersistence([{ role: "user", content: chinese }]),
    analysisTree: compactAnalysisTreeForPersistence({ content: chinese })
  });

  assert.ok(utf8Size(payload) < 1_000_000, `UTF-8 payload must remain bounded, got ${utf8Size(payload)}`);
});
