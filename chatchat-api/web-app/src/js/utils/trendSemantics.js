export const TREND_SEMANTICS_UPDATED_EVENT = "chatchat:trend-semantics-updated";
export const DEFAULT_TREND_KEYWORDS = Object.freeze([
  "涨跌", "涨幅", "跌幅", "盈亏", "收益", "回报", "增长", "增幅", "同比", "环比",
  "变化", "变动", "净增", "change", "profit", "return", "growth", "delta", "pnl"
]);
export const TREND_COLORS = {
  up: "#e5484d",
  down: "#16a36a",
  neutral: "#98a2b3"
};

let activeKeywords = [...DEFAULT_TREND_KEYWORDS];

function validColor(value, fallback) {
  const color = String(value || "").trim();
  return /^#[0-9a-f]{6}$/i.test(color) ? color.toLowerCase() : fallback;
}

export function configureTrendSemantics(config = {}) {
  const keywords = Array.isArray(config.keywords)
    ? [...new Set(config.keywords.map((item) => String(item || "").trim().toLowerCase()).filter(Boolean))]
    : [];
  activeKeywords = keywords.length ? keywords : [...DEFAULT_TREND_KEYWORDS];
  TREND_COLORS.up = validColor(config.upColor, "#e5484d");
  TREND_COLORS.down = validColor(config.downColor, "#16a36a");
  TREND_COLORS.neutral = validColor(config.neutralColor, "#98a2b3");

  if (typeof document !== "undefined") {
    const root = document.documentElement;
    root.style.setProperty("--trend-up-color", TREND_COLORS.up);
    root.style.setProperty("--trend-down-color", TREND_COLORS.down);
    root.style.setProperty("--trend-neutral-color", TREND_COLORS.neutral);
    if (typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent(TREND_SEMANTICS_UPDATED_EVENT, { detail: trendSemanticSnapshot() }));
    }
  }
  return trendSemanticSnapshot();
}

export function trendSemanticSnapshot() {
  return {
    keywords: [...activeKeywords],
    colors: { ...TREND_COLORS }
  };
}

export function numericTrendValue(value) {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  const normalized = String(value ?? "").trim().replace(/,/g, "").replace(/%$/, "");
  if (!normalized) return null;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

export function isDirectionalMetric(value = "") {
  const label = String(value || "").toLowerCase();
  return activeKeywords.some((keyword) => label.includes(keyword));
}

export function trendState(value) {
  const number = numericTrendValue(value);
  if (number === null || number === 0) return "neutral";
  return number > 0 ? "up" : "down";
}

export function trendColor(value) {
  return TREND_COLORS[trendState(value)];
}
