/** Wire compatibility: unlimited is explicit; counters themselves always remain finite. */
export const isUnlimited = value => value === 'unlimited' || value === -1;
export function validLimit(value) { return isUnlimited(value) || Number.isSafeInteger(value) && value >= 0; }
export function hasLimitRoom(limit, used) {
  if (!validLimit(limit) || !Number.isSafeInteger(used) || used < 0) throw new TypeError('Invalid execution limit/counter');
  return isUnlimited(limit) || used < limit;
}
export function sdkIterationLimit(limit) {
  if (!validLimit(limit)) throw new TypeError('Invalid model call limit');
  // Official @cline/agents loops until stop when maxIterations is undefined.
  return isUnlimited(limit) ? undefined : Math.max(1,limit);
}
