import assert from "node:assert/strict";
import test from "node:test";

import { answerPdfFileName, reportExportReference } from "./answerPdfExport.js";

test("PDF requests only identify persisted reports", () => {
  const message = { id: "msg-1", content: "unsaved content" };
  assert.deepEqual(reportExportReference({ reference: "report-1", conversationId: "chat-1", message }), { reportId: "report-1" });
  assert.deepEqual(reportExportReference({ conversationId: "chat-1", message }), { conversationId: "chat-1", messageId: "msg-1" });
  assert.throws(() => reportExportReference({ message }));
});

test("PDF filename removes filesystem control characters", () => {
  const source = { querySelector: () => ({ textContent: "客户/资产:分析*报告?" }) };
  const name = answerPdfFileName({ timestamp: "2026-08-05T12:00:00+08:00" }, source);
  assert.equal(name, "客户 资产 分析 报告-2026-08-05.pdf");
});
