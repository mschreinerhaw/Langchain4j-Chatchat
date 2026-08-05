import assert from "node:assert/strict";
import test from "node:test";

import { answerPdfFileName, calculatePdfSlices } from "./answerPdfExport.js";

test("PDF slices cover a long canvas without gaps or overflow", () => {
  const layout = calculatePdfSlices(1900, 10000);
  assert.ok(layout.slices.length > 1);
  assert.equal(layout.slices[0].offset, 0);
  for (let index = 1; index < layout.slices.length; index += 1) {
    assert.equal(layout.slices[index].offset, layout.slices[index - 1].offset + layout.slices[index - 1].height);
  }
  const last = layout.slices.at(-1);
  assert.equal(last.offset + last.height, 10000);
});

test("PDF filename removes filesystem control characters", () => {
  const source = { querySelector: () => ({ textContent: "客户/资产:分析*报告?" }) };
  const name = answerPdfFileName({ timestamp: "2026-08-05T12:00:00+08:00" }, source);
  assert.equal(name, "客户 资产 分析 报告-2026-08-05.pdf");
});

test("PDF slicing prefers a nearby semantic block boundary", () => {
  const layout = calculatePdfSlices(1900, 8000, [2300, 2600, 3000, 5100]);

  assert.equal(layout.slices[0].height, 2600);
  assert.equal(layout.slices[1].offset, 2600);
});
