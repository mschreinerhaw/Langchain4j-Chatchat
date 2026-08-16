import test from "node:test";
import assert from "node:assert/strict";

import VisualizationRenderer, { formatDataValue } from "./VisualizationRenderer.js";

test("chart data values are never abbreviated", () => {
  assert.equal(formatDataValue(2700), "2,700");
  assert.equal(formatDataValue(2689), "2,689");
  assert.equal(formatDataValue(1234567.89), "1,234,567.89");
  assert.equal(formatDataValue("2689.00"), "2689.00");
  assert.doesNotMatch(formatDataValue(2700), /[KMB]/i);
});

test("tooltip prefers the original row value over the plotted numeric projection", () => {
  const context = {
    xKey: "time",
    xAxisLabel: "时间",
    formatDataValue
  };
  const html = VisualizationRenderer.methods.formatTooltip.call(context, [{
    name: "10:35",
    seriesName: "数量",
    data: {
      value: 2689,
      yKey: "quantity",
      row: { time: "10:35", quantity: "2689.00" }
    }
  }], { quantity: "数量" });

  assert.match(html, /2689\.00/);
  assert.doesNotMatch(html, /2\.7K/i);
});
