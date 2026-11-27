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

export function reportExportReference({ reference, conversationId, message }) {
  if (reference) return { reportId: reference };
  if (!conversationId || !message?.id) throw new Error("报告尚未归档");
  return { conversationId, messageId: message.id };
}

export async function exportRenderedAnswerToPdf({ sourceElement, message = {}, fileName = "", reference = "", conversationId }) {
  const body = reportExportReference({ reference, conversationId, message });
  const { apiRequest } = await import("../../services/api.js");
  const pdf = await apiRequest("/report-exports/pdf", { method: "POST", body: JSON.stringify(body), responseType: "pdf" });
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
}
