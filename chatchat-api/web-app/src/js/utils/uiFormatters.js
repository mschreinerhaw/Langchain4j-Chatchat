const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false
});

export function formatDateTime(value, fallback = "时间未知") {
  if (value === null || value === undefined || value === "") {
    return fallback;
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return fallback;
  }
  const parts = Object.fromEntries(DATE_TIME_FORMATTER.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

export function errorMessage(error, fallback = "操作失败，请稍后重试") {
  const message = typeof error === "string" ? error : error?.message;
  return String(message || fallback).trim() || fallback;
}
