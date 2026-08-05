export function selectCompleteMessageContent(structuredAnswer, fallbackContent) {
  const structured = String(structuredAnswer ?? "").trim();
  const fallback = String(fallbackContent ?? "").trim();
  if (!structured) {
    return fallback;
  }
  if (!fallback) {
    return structured;
  }
  if (fallback.length > structured.length && fallback.startsWith(structured)) {
    return fallback;
  }
  const structuredPrefix = structured.replace(
    /\n\.\.\.\[[^\]\n]*truncated; originalBytes=\d+\]$/,
    ""
  );
  if (structuredPrefix !== structured && fallback.startsWith(structuredPrefix)) {
    return fallback;
  }
  return structured;
}
