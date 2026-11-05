/** Estimates only; Runtime admissions and actual provider usage remain authoritative. */
export function modelInputBudget(info = {}, outputReserve = 0, safetyRatio = 0.1) {
  if (!Number.isFinite(safetyRatio) || safetyRatio < 0 || safetyRatio >= 1)
    throw new TypeError('Invalid context safety margin');
  const window = info.contextWindowTokens ?? info.contextWindow;
  const reserve = info.outputReserveTokens ?? outputReserve;
  const limits = [];
  if (Number.isSafeInteger(info.maxInputTokens) && info.maxInputTokens > 0) limits.push(info.maxInputTokens);
  if (Number.isSafeInteger(window) && window > 0) {
    if (!Number.isSafeInteger(reserve) || reserve < 0) throw new TypeError('Invalid output reservation');
    const input = window - reserve - Math.ceil(window * safetyRatio);
    if (input < 1) throw new TypeError('Model context cannot fit output reserve and safety margin');
    limits.push(input);
  }
  return limits.length ? Math.min(...limits) : undefined;
}
