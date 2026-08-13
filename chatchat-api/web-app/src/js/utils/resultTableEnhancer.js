import { isDirectionalMetric, trendState } from "./trendSemantics.js";

function parseNumber(value) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const raw = String(value ?? "").trim();
  if (!raw || /^[-+]?0\d+/.test(raw)) return null;
  const parsed = Number(raw.replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

function tableTitle(table, index) {
  let node = table.previousElementSibling;
  let distance = 0;
  while (node && distance < 6) {
    if (/^H[1-6]$/i.test(node.tagName)) {
      return String(node.textContent || "").trim();
    }
    node = node.previousElementSibling;
    distance += 1;
  }
  return `查询结果 ${index + 1}`;
}

function tablePayload(table, index) {
  const columns = [...table.querySelectorAll("thead th")]
    .map((cell) => String(cell.textContent || "").trim())
    .filter(Boolean);
  if (columns.length < 2) return null;

  const rows = [...table.querySelectorAll("tbody tr")]
    .map((row) => {
      const cells = [...row.querySelectorAll("td")];
      if (!cells.length) return null;
      return Object.fromEntries(columns.map((column, columnIndex) => {
        const raw = String(cells[columnIndex]?.textContent || "").trim();
        const number = parseNumber(raw);
        return [column, number !== null && raw !== "" ? number : raw];
      }));
    })
    .filter(Boolean);
  if (!rows.length) return null;

  return { title: tableTitle(table, index), columns, rows };
}

function removeEmptySourceColumns(table) {
  const headers = [...table.querySelectorAll("thead th")];
  const rows = [...table.querySelectorAll("tbody tr")];
  if (!headers.length || !rows.length) return;

  const sourceColumns = headers
    .map((header, index) => ({ index, header, label: String(header.textContent || "").trim() }))
    .filter(({ label }) => /^(?:来源|主要来源|相关证据|证据来源|引用|引用来源|sources?)$/i.test(label));
  sourceColumns.forEach(({ index, header }) => {
    header.classList.add("source-column");
    rows.forEach((row) => {
      const cell = row.children[index];
      if (!cell) return;
      cell.classList.add("source-column");
      const value = String(cell.textContent || "").replace(/\s+/g, " ").trim();
      if (value) cell.title = value;
      cell.querySelectorAll("a[href]").forEach((link) => {
        link.title = link.getAttribute("href") || link.textContent || value;
      });
    });
  });
  sourceColumns
    .filter(({ index }) => rows.every((row) => {
      const cell = row.children[index];
      if (!cell) return true;
      if (cell.querySelector("a[href], img[src]")) return false;
      return /^(?:|-|—|–|无|暂无|N\/?A|null)$/i.test(String(cell.textContent || "").trim());
    }))
    .map(({ index }) => index)
    .sort((left, right) => right - left)
    .forEach((index) => table.querySelectorAll("tr").forEach((row) => row.children[index]?.remove()));
}

function decorateDirectionalCells(table) {
  const headers = [...table.querySelectorAll("thead th")];
  const rows = [...table.querySelectorAll("tbody tr")];
  headers.forEach((header, index) => {
    const label = String(header.textContent || "").trim();
    if (!isDirectionalMetric(label)) return;
    header.classList.add("trend-column");
    rows.forEach((row) => {
      const cell = row.children[index];
      if (!cell) return;
      cell.classList.add("trend-value", `trend-${trendState(cell.textContent)}`);
    });
  });
}

export function enhanceResultTables(html = "") {
  if (typeof DOMParser === "undefined" || !String(html || "").includes("<table")) return html;
  try {
    const document = new DOMParser().parseFromString(`<div>${html}</div>`, "text/html");
    const root = document.body.firstElementChild;
    root.querySelectorAll("th").forEach((cell) => {
      if (/^(?:相关证据|证据来源|引用|引用来源)$/.test(String(cell.textContent || "").trim())) {
        cell.textContent = "主要来源";
      }
    });
    root.querySelectorAll("td").forEach((cell) => {
      const sourceTags = [...cell.querySelectorAll("a.web-citation-link")];
      if (sourceTags.length <= 2) return;
      sourceTags.slice(2).forEach((tag) => tag.classList.add("source-tag-overflow-hidden"));
      const toggle = document.createElement("button");
      toggle.type = "button";
      toggle.className = "source-tag-overflow-toggle";
      toggle.dataset.sourceTagsToggle = "collapsed";
      toggle.textContent = `+${sourceTags.length - 2}`;
      toggle.setAttribute("aria-label", `展开其余 ${sourceTags.length - 2} 条来源`);
      cell.append(toggle);
    });

    const tables = [...root.querySelectorAll("table")];
    tables.forEach(removeEmptySourceColumns);
    tables.forEach(decorateDirectionalCells);
    const enhanced = tables
      .map((table, index) => ({ table, payload: tablePayload(table, index) }))
      .filter(({ payload }) => payload);
    let firstWrapper = null;
    enhanced.forEach(({ table, payload }) => {
      const encodedPayload = encodeURIComponent(JSON.stringify(payload));
      const wrapper = document.createElement("section");
      wrapper.className = "query-result-table-card";
      wrapper.dataset.resultChartPayload = encodedPayload;
      const toolbar = document.createElement("div");
      toolbar.className = "query-result-table-toolbar";
      const summary = document.createElement("span");
      summary.textContent = `${payload.rows.length} 行 / ${payload.columns.length} 列`;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "query-result-chart-button";
      button.dataset.resultChartPayload = encodedPayload;
      button.textContent = "图形分析";
      toolbar.append(summary, button);
      table.parentNode.insertBefore(wrapper, table);
      wrapper.append(toolbar, table);
      firstWrapper ||= wrapper;
    });

    if (enhanced.length > 1 && firstWrapper?.parentNode) {
      const payload = {
        title: "多数据集对比",
        datasets: enhanced.map((item, index) => ({ id: `dataset_${index + 1}`, ...item.payload }))
      };
      const card = document.createElement("section");
      card.className = "query-result-table-card query-result-multi-dataset-card";
      const toolbar = document.createElement("div");
      toolbar.className = "query-result-table-toolbar";
      const summary = document.createElement("span");
      summary.textContent = `共 ${enhanced.length} 个数据集`;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "query-result-chart-button";
      button.dataset.resultChartPayload = encodeURIComponent(JSON.stringify(payload));
      button.textContent = "对比数据集";
      toolbar.append(summary, button);
      card.append(toolbar);
      firstWrapper.parentNode.insertBefore(card, firstWrapper);
    }
    return root.innerHTML;
  } catch (error) {
    console.warn("Enhance result table failed", error);
    return html;
  }
}
