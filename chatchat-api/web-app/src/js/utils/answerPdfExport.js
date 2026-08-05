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
  header.append(titleElement, subtitleElement);

  const answer = sourceElement.cloneNode(true);
  answer.removeAttribute("id");
  answer.style.cssText = "width:100%;max-width:none;overflow:visible";
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
  return [...host.querySelectorAll("h1, h2, h3, h4, h5, h6, p, table, pre, blockquote, ul, ol, section")]
    .filter((element) => !(
      element.matches("p")
      && element.previousElementSibling?.matches("h1, h2, h3, h4, h5, h6")
    ))
    .map((element) => (element.getBoundingClientRect().top - hostRect.top) * canvasScale)
    .filter((value) => value > 0);
}

export async function exportRenderedAnswerToPdf({ sourceElement, message = {}, fileName = "" }) {
  if (!sourceElement) {
    throw new Error("未找到可导出的回答内容");
  }
  const [{ default: html2canvas }, { jsPDF }] = await Promise.all([
    import("html2canvas"),
    import("jspdf")
  ]);
  const title = sourceElement.querySelector("h1, h2, h3")?.textContent?.trim()
    || message.agentName
    || "AI 回答";
  const subtitle = `${message.agentName || "AI 助手"} · ${new Date(message.timestamp || Date.now()).toLocaleString("zh-CN")}`;
  const host = createExportHost(sourceElement, title, subtitle);
  try {
    if (document.fonts?.ready) {
      await document.fonts.ready;
    }
    const canvas = await html2canvas(host, {
      backgroundColor: "#ffffff",
      logging: false,
      scale: host.scrollHeight > 14000 ? 1 : 2,
      useCORS: true,
      windowWidth: Math.max(document.documentElement.clientWidth, 900)
    });
    const layout = calculatePdfSlices(canvas.width, canvas.height, contentBreakpoints(host, canvas));
    const pdf = new jsPDF({ orientation: "portrait", unit: "mm", format: "a4", compress: true });
    layout.slices.forEach((slice, index) => {
      if (index > 0) {
        pdf.addPage("a4", "portrait");
      }
      const pageCanvas = document.createElement("canvas");
      pageCanvas.width = canvas.width;
      pageCanvas.height = slice.height;
      const context = pageCanvas.getContext("2d", { alpha: false });
      context.fillStyle = "#ffffff";
      context.fillRect(0, 0, pageCanvas.width, pageCanvas.height);
      context.drawImage(canvas, 0, slice.offset, canvas.width, slice.height, 0, 0, pageCanvas.width, pageCanvas.height);
      pdf.addImage(
        pageCanvas.toDataURL("image/jpeg", 0.94),
        "JPEG",
        PAGE_MARGIN_MM,
        PAGE_MARGIN_MM,
        layout.contentWidthMm,
        slice.height / layout.pixelsPerMm,
        undefined,
        "FAST"
      );
      pdf.setFontSize(9);
      pdf.setTextColor(120, 130, 145);
      pdf.text(`${index + 1} / ${layout.slices.length}`, A4_WIDTH_MM / 2, A4_HEIGHT_MM - 5, { align: "center" });
    });
    pdf.setProperties({ title, subject: "AI answer export", creator: "ChatChat" });
    pdf.save(fileName || answerPdfFileName(message, sourceElement));
  } finally {
    host.remove();
  }
}
