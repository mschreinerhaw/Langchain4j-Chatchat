/** Presentation only: keep archived titles and chart kind untouched. */
export function chartDisplayTitle(value) {
  return String(value || '').replace(/[（(]\s*plotly\s*[）)]/gi, '（动态）').replace(/\bplotly\b/gi, '动态');
}
