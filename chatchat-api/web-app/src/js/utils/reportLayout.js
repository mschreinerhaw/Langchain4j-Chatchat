import schema from "../../../../../chatchat-common/src/main/resources/runtime/report-layout.schema.json";

export const defaultReportPresentation = Object.freeze({
  strategy: "auto",
  template: "briefing",
  density: "comfortable",
  autoCharts: true,
  autoImages: true,
});
export function reportPresentation(value = {}) {
  const clean = { ...defaultReportPresentation };
  for (const [key, choices] of Object.entries({
    strategy: ["auto", "template", "concise"],
    template: ["briefing", "evidence-first"],
    density: ["comfortable", "compact"],
  })) {
    if (choices.includes(value?.[key])) clean[key] = value[key];
  }
  for (const key of ["autoCharts", "autoImages"])
    if (typeof value?.[key] === "boolean") clean[key] = value[key];
  return clean;
}

const blockTypes = new Set(schema.properties.blocks.items.properties.type.enum);
const safeId = (value) => typeof value === "string" && /^[A-Za-z0-9_-]{1,100}$/.test(value);
const safeRef = (value) =>
  typeof value === "string" && /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}$/.test(value);
function available(report) {
  return Object.fromEntries(
    ["metrics", "sections", "charts", "tables"].map((key, index) => [
      ["metric", "section", "chart", "table"][index],
      new Map(
        (Array.isArray(report[key]) ? report[key] : [])
          .filter((value) => value && typeof value === "object")
          .map((value, i) => [
            value.id || `${key === "sections" ? "section" : key}-${i + 1}`,
            value,
          ])
      ),
    ])
  );
}

export function checkedReportLayout(layout, report) {
  if (
    !layout ||
    layout.schemaVersion !== schema.$id ||
    !Array.isArray(layout.blocks) ||
    layout.blocks.length > 256 ||
    Object.keys(layout).some((key) => !["schemaVersion", "blocks"].includes(key))
  )
    throw new Error("Invalid report layout");
  const catalog = available(report),
    ids = new Set(),
    placements = new Set();
  return layout.blocks.map((block) => {
    if (
      !block ||
      !safeId(block.id) ||
      block.id.length > 64 ||
      ids.has(block.id) ||
      !blockTypes.has(block.type) ||
      Object.keys(block).some((key) => !["id", "type", "ref", "span", "emphasis"].includes(key))
    )
      throw new Error("Unsafe report component");
    ids.add(block.id);
    if (
      catalog[block.type]
        ? !safeRef(block.ref) || !catalog[block.type].has(block.ref)
        : block.ref !== undefined
    )
      throw new Error("Unknown report data reference");
    if (
      (block.span !== undefined && !["full", "half"].includes(block.span)) ||
      (block.emphasis !== undefined && !["normal", "important"].includes(block.emphasis))
    )
      throw new Error("Invalid report layout hint");
    const placement = `${block.type}:${block.ref || ""}`;
    if (placements.has(placement)) throw new Error("Duplicate report placement");
    placements.add(placement);
    return { ...block };
  });
}

/** Deterministic projection only: never creates metrics, data or charts. */
export function reportLayout(report = {}) {
  const preferences = reportPresentation(report.presentation);
  const catalog = available(report),
    blocks = [],
    seen = new Set();
  let warning = "";
  const add = (type, ref, options = {}) => {
    const key = `${type}:${ref || ""}`;
    if (seen.has(key)) return;
    if (
      (type === "summary" && !report.summary) ||
      (type === "limitations" && !report.limitations?.length) ||
      (type === "sources" && !report.dataSources?.length)
    )
      return;
    const value = catalog[type]?.get(ref);
    if (catalog[type] && !value) return;
    if (
      type === "chart" &&
      ((value.role === "illustration" && !preferences.autoImages) ||
        (value.role !== "illustration" && !preferences.autoCharts))
    )
      return;
    seen.add(key);
    blocks.push({
      id: `report-block-${blocks.length + 1}`,
      type,
      ref,
      value,
      span: type === "metric" ? "half" : "full",
      emphasis: "normal",
      ...options,
    });
  };
  if (report.layout) {
    try {
      for (const block of checkedReportLayout(report.layout, report))
        add(block.type, block.ref, block);
    } catch {
      warning = "排版描述未通过校验，已保留原分析并使用兼容布局。";
      blocks.length = 0;
      seen.clear();
    }
  }
  // Older manifests keep their authored order. Unplaced evidence is retained,
  // including sources and limitations; a model layout cannot silently hide it.
  add("summary");
  for (const ref of catalog.metric.keys()) add("metric", ref);
  for (const [ref, section] of catalog.section) {
    add("section", ref);
    for (const id of section.chartIds || []) add("chart", id);
    for (const id of section.tableIds || []) add("table", id);
  }
  for (const ref of catalog.chart.keys()) add("chart", ref);
  for (const ref of catalog.table.keys()) add("table", ref);
  add("limitations");
  add("sources");
  if (preferences.strategy === "template") {
    const order =
      preferences.template === "evidence-first"
        ? ["table", "chart", "section", "metric", "summary", "limitations", "sources"]
        : ["summary", "section", "metric", "chart", "table", "limitations", "sources"];
    blocks.sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type));
  }
  return { blocks, preferences, warning };
}
