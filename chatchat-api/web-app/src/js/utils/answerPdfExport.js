import { apiRequest } from "../../services/api.js";

const A4_WIDTH_MM = 210;
const A4_HEIGHT_MM = 297;
const PAGE_MARGIN_MM = 10;
const FOOTER_SPACE_MM = 8;

export function answerPdfFileName(message = {}, sourceElement = null) {
  const heading = sourceElement?.querySelector?.("h1, h2, h3")?.textContent || "";
  const fallback = message.agentName || message.modelName || "AI回答";
  const base = String(heading || fallback)
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 64) || "AI回答";
  const date = new Date(message.timestamp || Date.now()).toISOString().slice(0, 10);
  return `${base}-${date}.pdf`;
}

export function calculatePdfSlices(canvasWidth, canvasHeight, breakpoints = []) {
  const contentWidthMm = A4_WIDTH_MM - PAGE_MARGIN_MM * 2;
  const contentHeightMm = A4_HEIGHT_MM - PAGE_MARGIN_MM * 2 - FOOTER_SPACE_MM;
  const pixelsPerMm = canvasWidth / contentWidthMm;
  const pageHeightPixels = Math.max(1, Math.floor(contentHeightMm * pixelsPerMm));
  const safeBreakpoints = [...new Set((Array.isArray(breakpoints) ? breakpoints : [])
    .map((value) => Math.round(Number(value)))
    .filter((value) => Number.isFinite(value) && value > 0 && value < canvasHeight))]
    .sort((left, right) => left - right);
  const slices = [];
  let offset = 0;
  while (offset < canvasHeight) {
    const target = Math.min(canvasHeight, offset + pageHeightPixels);
    let end = target;
    if (target < canvasHeight) {
      const minimumUsefulBreak = offset + pageHeightPixels * 0.6;
      const candidates = safeBreakpoints.filter((value) => value >= minimumUsefulBreak && value <= target);
      if (candidates.length) {
        end = candidates.at(-1);
      }
    }
    slices.push({ offset, height: Math.max(1, end - offset) });
    offset = end;
  }
  return { contentWidthMm, contentHeightMm, pixelsPerMm, slices };
}

function createExportHost(sourceElement, title, subtitle) {
  const host = document.createElement("section");
  host.className = "answer-pdf-export-host";
  host.style.cssText = [
    "position:fixed",
    "left:-100000px",
    "top:0",
    "width:794px",
    "box-sizing:border-box",
    "padding:46px 50px 54px",
    "background:#ffffff",
    "color:#1f2937",
    "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei','PingFang SC',sans-serif"
  ].join(";");

  const header = document.createElement("header");
  header.style.cssText = "margin-bottom:28px;padding-bottom:16px;border-bottom:2px solid #e5edf8";
  const titleElement = document.createElement("strong");
  titleElement.textContent = title || "AI 回答";
  titleElement.style.cssText = "display:block;color:#14213d;font-size:20px;line-height:1.4";
  const subtitleElement = document.createElement("small");
  subtitleElement.textContent = subtitle || "";
  subtitleElement.style.cssText = "display:block;margin-top:7px;color:#64748b;font-size:12px";
  const hasReportHeader = sourceElement.matches('.python-visual-report, .analytical-report')
    || sourceElement.querySelector('.python-visual-report, .analytical-report');
  if (!hasReportHeader) header.append(titleElement);
  header.append(subtitleElement);

  const answer = sourceElement.cloneNode(true);
  const copiedCanvases = [...answer.querySelectorAll('canvas')];
  sourceElement.querySelectorAll('canvas').forEach((canvas, index) => {
    const copy = copiedCanvases[index];
    if (copy) {
      const picture = document.createElement('img');
      picture.src = canvas.toDataURL('image/png');
      picture.style.cssText = 'display:block;width:100%;height:auto';
      copy.replaceWith(picture);
    }
  });
  answer.querySelectorAll('iframe').forEach((frame) => {
    if (!frame.dataset.pdfImage) throw new Error('图表尚未准备完成，请稍后重试');
    const picture = document.createElement('img');
    picture.src = frame.dataset.pdfImage;
    picture.alt = frame.title;
    picture.style.cssText = 'display:block;width:100%;height:auto';
    frame.replaceWith(picture);
  });
  answer.removeAttribute("id");
  answer.style.cssText = "width:100%;max-width:none;overflow:visible";
  if (sourceElement.matches('.python-visual-report, .analytical-report')) {
    answer.style.cssText += ';border:0;border-radius:0;box-shadow:none;padding:0';
  }
  answer.querySelectorAll("button, [data-source-tags-toggle]").forEach((element) => element.remove());
  answer.querySelectorAll("details").forEach((element) => { element.open = true; });
  answer.querySelectorAll(".source-tag-overflow-hidden").forEach((element) => {
    element.classList.remove("source-tag-overflow-hidden");
  });
  host.append(header, answer);
  document.body.append(host);
  return host;
}

function contentBreakpoints(host, canvas) {
  const hostRect = host.getBoundingClientRect();
  const canvasScale = canvas.width / Math.max(1, hostRect.width);
  return [...host.querySelectorAll("h1, h2, h3, h4, h5, h6, p, table, pre, blockquote, ul, ol, section, figure")]
    .filter((element) => !(
      element.matches("p")
      && element.previousElementSibling?.matches("h1, h2, h3, h4, h5, h6")
    ))
    .map((element) => (element.getBoundingClientRect().top - hostRect.top) * canvasScale)
    .filter((value) => value > 0);
}

function snapshotReport(source) {
  const requestId = Array.from(crypto.getRandomValues(new Uint32Array(4))).join("-");
  return new Promise(resolve => {
    const finish = html => {
      clearTimeout(timeout);
      window.removeEventListener("message", listen);
      resolve(html);
    };
    const listen = event => {
      const data = event.data;
      if (event.source !== source.contentWindow || data?.type !== "runtime-html-report-pdf-ready"
          || data.requestId !== requestId || typeof data.html !== "string") return;
      finish(data.html.length <= 32 * 1024 * 1024 ? data.html : source.srcdoc);
    };
    const timeout = setTimeout(() => finish(source.srcdoc), 1250);
    window.addEventListener("message", listen);
    source.contentWindow.postMessage({ type: "runtime-html-report-pdf-snapshot", requestId }, "*");
  });
}

async function staticReportFrame(source) {
  const frame = document.createElement("iframe");
  frame.setAttribute("sandbox", "allow-same-origin");
  frame.setAttribute("aria-hidden", "true");
  frame.style.cssText = "position:fixed;left:-10000px;top:0;width:794px;height:1000px;border:0";
  const parsed = new DOMParser().parseFromString(await snapshotReport(source), "text/html");
  parsed.querySelectorAll("script, iframe, object, embed, form, link, meta, base").forEach(node => node.remove());
  const policy = parsed.createElement("meta");
  policy.httpEquiv = "Content-Security-Policy";
  policy.content = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'";
  parsed.head.prepend(policy);
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("报告加载超时")), 8000);
      frame.onload = () => { clearTimeout(timeout); resolve(); };
      frame.srcdoc = "<!doctype html>" + parsed.documentElement.outerHTML;
      document.body.append(frame);
    });
    return frame;
  } catch (error) {
    frame.remove();
    throw error;
  }
}

export async function exportRenderedAnswerToPdf({ sourceElement, message = {}, fileName = "", reference = "" }) {
  if (!sourceElement) throw new Error("未找到可导出的回答内容");
  const tasks = [];
  sourceElement.querySelectorAll(".python-report-chart").forEach(chart => {
    chart.dispatchEvent(new CustomEvent("report:prepare-export", { detail: { tasks } }));
  });
  await Promise.all(tasks);
  const { default: html2canvas } = await import("html2canvas");
  const title = sourceElement.querySelector("h1, h2, h3")?.textContent?.trim() || message.agentName || "AI 回答";
  const subtitle = `${message.agentName || "AI 助手"} · ${new Date(message.timestamp || Date.now()).toLocaleString("zh-CN")}`;
  const reportFrame = sourceElement.matches(".html-report-viewer") ? sourceElement.querySelector("iframe") : null;
  if (sourceElement.matches(".html-report-viewer") && !reportFrame?.srcdoc) throw new Error("报告尚未加载完成");
  const frame = reportFrame ? await staticReportFrame(reportFrame) : null;
  const host = frame ? frame.contentDocument.body : createExportHost(sourceElement, title, subtitle);
  try {
    await host.ownerDocument.fonts?.ready;
    await Promise.all([...host.querySelectorAll("img")].map(async picture => {
      picture.loading = "eager";
      await picture.decode().catch(() => picture.remove());
    }));
    if (host.scrollHeight > 30000) throw new Error("报告超出 PDF 导出页数限制");
    const canvas = await html2canvas(host, {
      backgroundColor: "#ffffff",
      logging: false,
      scale: host.scrollHeight > 14000 ? 1 : 2,
      useCORS: !frame,
      windowWidth: Math.max(host.ownerDocument.documentElement.clientWidth, 794)
    });
    const { slices } = calculatePdfSlices(canvas.width, canvas.height, contentBreakpoints(host, canvas));
    if (slices.length === 0 || slices.length > 32) throw new Error("报告超出 PDF 导出页数限制");
    const form = new FormData();
    form.append("reference", String(reference || message.taskId || message.id || "report"));
    let totalBytes = 0;
    for (const [index, slice] of slices.entries()) {
      const page = document.createElement("canvas");
      page.width = canvas.width;
      page.height = slice.height;
      const context = page.getContext("2d", { alpha: false });
      context.fillStyle = "#ffffff";
      context.fillRect(0, 0, page.width, page.height);
      context.drawImage(canvas, 0, slice.offset, canvas.width, slice.height, 0, 0, page.width, page.height);
      const image = await new Promise(resolve => page.toBlob(resolve, "image/jpeg", 0.9));
      if (!image || (totalBytes += image.size) > 10 * 1024 * 1024) throw new Error("报告超出 PDF 导出大小限制");
      form.append("pages", image, `page-${index + 1}.jpg`);
    }
    const pdf = await apiRequest("/report-exports/pdf", { method: "POST", body: form, responseType: "pdf" });
    const url = URL.createObjectURL(pdf);
    try {
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName || answerPdfFileName(message, sourceElement);
      document.body.append(link);
      link.click();
      link.remove();
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  } finally {
    if (frame) frame.remove(); else host.remove();
  }
}
