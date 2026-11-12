/**
 * Synthetic Acceptance Report — Interactive Logic
 * Loads stats.json via local fetch and populates the report DOM.
 */
(function () {
  "use strict";

  var STATS_URL = "stats.json";

  /**
   * Format a number for display: integers without decimals, otherwise 1 decimal.
   */
  function fmt(n) {
    if (n === undefined || n === null) return "—";
    return Number.isInteger(n) ? n.toString() : n.toFixed(1);
  }

  /**
   * Populate the summary statistics cards.
   */
  function renderSummary(summary) {
    var el = document.getElementById("stat-count");
    if (el) el.textContent = fmt(summary.count);
    el = document.getElementById("stat-total");
    if (el) el.textContent = fmt(summary.total);
    el = document.getElementById("stat-mean");
    if (el) el.textContent = fmt(summary.mean);
  }

  /**
   * Populate the category comparison table.
   */
  function renderCategoryTable(categories) {
    var tbody = document.getElementById("category-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";
    categories.forEach(function (c) {
      var tr = document.createElement("tr");
      var tdCat = document.createElement("td");
      tdCat.textContent = c.category;
      var tdAmt = document.createElement("td");
      tdAmt.textContent = fmt(c.amount);
      var tdCnt = document.createElement("td");
      tdCnt.textContent = c.count;
      tr.appendChild(tdCat);
      tr.appendChild(tdAmt);
      tr.appendChild(tdCnt);
      tbody.appendChild(tr);
    });
  }

  /**
   * Populate the original rows table (hidden by default).
   */
  function renderRowsTable(rows) {
    var tbody = document.getElementById("rows-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";
    rows.forEach(function (r, i) {
      var tr = document.createElement("tr");
      var tdIdx = document.createElement("td");
      tdIdx.textContent = i + 1;
      var tdCat = document.createElement("td");
      tdCat.textContent = r.category;
      var tdAmt = document.createElement("td");
      tdAmt.textContent = fmt(r.amount);
      tr.appendChild(tdIdx);
      tr.appendChild(tdCat);
      tr.appendChild(tdAmt);
      tbody.appendChild(tr);
    });
  }

  /**
   * Populate source attribution.
   */
  function renderSource(source) {
    var el = document.getElementById("source-file");
    if (el && source) el.textContent = source.file || "—";
  }

  /**
   * Wire up the show/hide toggle for original rows.
   */
  function initToggle() {
    var btn = document.getElementById("toggle-rows-btn");
    var panel = document.getElementById("rows-panel");
    if (!btn || !panel) return;

    btn.addEventListener("click", function () {
      var isVisible = panel.classList.contains("visible");
      if (isVisible) {
        panel.classList.remove("visible");
        btn.classList.remove("active");
        btn.querySelector(".btn-label").textContent = "Show Original Rows";
      } else {
        panel.classList.add("visible");
        btn.classList.add("active");
        btn.querySelector(".btn-label").textContent = "Hide Original Rows";
      }
    });
  }

  /**
   * Show an error message if data loading fails.
   */
  function showError(msg) {
    var container = document.getElementById("report-content");
    if (container) {
      container.innerHTML =
        '<div class="card"><p style="color:#c0392b;">Error loading report data: ' +
        msg +
        "</p></div>";
    }
  }

  /**
   * Main initialization: fetch stats.json and render.
   */
  function init() {
    initToggle();

    fetch(STATS_URL)
      .then(function (response) {
        if (!response.ok) {
          throw new Error("HTTP " + response.status + " " + response.statusText);
        }
        return response.json();
      })
      .then(function (data) {
        if (data.summary) renderSummary(data.summary);
        if (data.categories) renderCategoryTable(data.categories);
        if (data.rows) renderRowsTable(data.rows);
        if (data.source) renderSource(data.source);
      })
      .catch(function (err) {
        showError(err.message);
      });
  }

  // Run on DOM ready
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
