import assert from "node:assert/strict";
import {
  coerceChartMetricRows,
  isNumericChartColumn,
  parseChartNumber,
  selectChartMetricKey
} from "./chartDatasetTypes.js";

const columns = ["发生时间", "业务科目", "收入金额", "付出金额", "本次资金余额"];
const rows = [
  { 发生时间: "17:20:46", 业务科目: "卖出成交清算资金", 收入金额: "104,801.34", 付出金额: "0.00", 本次资金余额: "385,954.96" },
  { 发生时间: "17:20:46", 业务科目: "买入成交清算资金", 收入金额: "0.00", 付出金额: "121,735.33", 本次资金余额: "265,008.22" }
];

assert.equal(parseChartNumber("104,801.34"), 104801.34);
assert.equal(parseChartNumber("卖出成交清算资金"), null);
assert.equal(isNumericChartColumn(rows, "业务科目"), false);
assert.equal(isNumericChartColumn(rows, "收入金额"), true);

assert.equal(
  selectChartMetricKey(columns, rows, "发生时间", "业务科目"),
  "收入金额",
  "分类文本列不能成为自动选择的 Y 轴指标"
);

const categoryRows = coerceChartMetricRows(rows, "业务科目");
assert.equal(categoryRows[0].业务科目, "卖出成交清算资金");
assert.equal(categoryRows[1].业务科目, "买入成交清算资金");
assert.notEqual(categoryRows[0], rows[0], "返回行应与原始数据隔离");

const metricRows = coerceChartMetricRows(rows, "收入金额");
assert.equal(metricRows[0].收入金额, 104801.34);
assert.equal(metricRows[1].收入金额, 0, "合法的数值零必须保留");
assert.equal(metricRows[0].业务科目, "卖出成交清算资金");

console.log("chartDatasetTypes tests passed");
