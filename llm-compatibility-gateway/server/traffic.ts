export const trafficRanges = { '1h': 60, '6h': 360, '24h': 1440 } as const;
export type TrafficRange = keyof typeof trafficRanges;

type TrafficSample = {
  time: string;
  status: number;
  elapsedMs: number | null;
  firstTokenMs: number | null;
};

const bucketCount = 12;
const emptyCounts = () => ({ success: 0, failed: 0, elapsed: 0, elapsedCount: 0, firstToken: 0, firstTokenCount: 0 });

function summarize(counts: ReturnType<typeof emptyCounts>) {
  return {
    requests: counts.success + counts.failed,
    success: counts.success,
    failed: counts.failed,
    averageMs: counts.elapsedCount ? Math.round(counts.elapsed / counts.elapsedCount) : null,
    averageFirstTokenMs: counts.firstTokenCount ? Math.round(counts.firstToken / counts.firstTokenCount) : null,
  };
}

export function trafficStart(range: TrafficRange, now: number) {
  return now - trafficRanges[range] * 60_000;
}

export function summarizeTraffic(rows: TrafficSample[], range: TrafficRange, now: number) {
  const from = trafficStart(range, now);
  const interval = (now - from) / bucketCount;
  const buckets = Array.from({ length: bucketCount }, emptyCounts);
  const totals = emptyCounts();
  for (const row of rows) {
    const time = Date.parse(row.time);
    if (!Number.isFinite(time) || time < from || time > now) continue;
    const bucket = buckets[Math.min(bucketCount - 1, Math.floor((time - from) / interval))];
    for (const counts of [bucket, totals]) {
      if (!(row.status >= 200 && row.status < 300)) {
        counts.failed++;
        continue;
      }
      counts.success++;
      if (typeof row.elapsedMs === 'number') {
        counts.elapsed += row.elapsedMs;
        counts.elapsedCount++;
      }
      if (typeof row.firstTokenMs === 'number') {
        counts.firstToken += row.firstTokenMs;
        counts.firstTokenCount++;
      }
    }
  }
  return { range, interval, from, to: now, totals: summarize(totals), buckets: buckets.map(summarize) };
}

export type TrafficTrend = ReturnType<typeof summarizeTraffic>;
