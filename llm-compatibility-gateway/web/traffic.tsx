import { Card, Empty, Tooltip } from 'antd';
import type { TrafficTrend } from '../server/traffic.js';

type Bucket = TrafficTrend['buckets'][number];
type Series = { label: string; field: keyof Bucket; color: string };

const requests: Series[] = [
  { label: '成功', field: 'success', color: '#087f72' },
  { label: '失败', field: 'failed', color: '#c24138' },
];
const latency: Series[] = [
  { label: '平均总耗时', field: 'averageMs', color: '#3270a8' },
  { label: '平均首 Token 延迟', field: 'averageFirstTokenMs', color: '#087f72' },
];
const timeFormat = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
const detailFormat = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hour12: false });
const numberFormat = new Intl.NumberFormat('zh-CN');

function Chart({ title, trend, series, unit, stacked }: {
  title: string; trend: TrafficTrend; series: Series[]; unit: string; stacked?: boolean;
}) {
  const max = Math.max(...trend.buckets.map(bucket => stacked ? bucket.requests :
    Math.max(...series.map(item => bucket[item.field] ?? 0))));
  const step = Math.max(1, Math.ceil(max / 2));
  const scale = step * 2;
  const hasData = stacked ? trend.totals.requests > 0 : series.some(item => trend.totals[item.field] != null);
  const ticks = [0, 4, 8, trend.buckets.length];
  return <Card title={title}>
    <ul className="chart-legend" aria-label={title + '图例'}>
      {series.map(item => <li key={item.field}><span style={{ backgroundColor: item.color }} />{item.label}</li>)}
      <li>{stacked ? '单位 次' : '成功请求 · ms'}</li>
    </ul>
    {!hasData ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} className="chart-empty"
      description={stacked ? '所选时段暂无请求' : '所选时段暂无成功请求的耗时数据'} /> :
      <div className="traffic-chart" role="group" aria-label={title + '，单位' + unit}>
        <div className="chart-scale" aria-hidden="true">{[scale, step, 0].map(value => <span key={value}>
          {numberFormat.format(value)}</span>)}</div>
        <div className="chart-plot">
          {trend.buckets.map((bucket, index) => {
            const time = trend.from + index * trend.interval;
            const label = detailFormat.format(time) + ' 至 ' + detailFormat.format(time + trend.interval);
            const details = series.map(item => {
              const value = bucket[item.field];
              return item.label + ' ' + (value == null ? '—' : numberFormat.format(value) + ' ' + unit);
            });
            return <Tooltip key={index} trigger={['hover', 'focus']} title={<>{label}{details.map(detail =>
              <div key={detail}>{detail}</div>)}</>}>
              <button type="button" className={'chart-column' + (stacked ? ' chart-stacked' : '')}
                aria-label={[label, ...details].join('，')}>
                {series.map(item => <span key={item.field} style={{
                  height: (100 * (bucket[item.field] ?? 0) / scale) + '%', backgroundColor: item.color,
                }} />)}
              </button>
            </Tooltip>;
          })}
        </div>
        <div className="chart-time" aria-hidden="true">{ticks.map(index =>
          <span key={index}>{timeFormat.format(trend.from + index * trend.interval)}</span>)}</div>
      </div>}
  </Card>;
}

export function TrafficCharts({ trend }: { trend?: TrafficTrend }) {
  if (!trend) return <div className="traffic-grid after"><Card title="请求量趋势" loading /><Card title="响应耗时趋势" loading /></div>;
  return <div className="traffic-grid after">
    <Chart title="请求量趋势" trend={trend} series={requests} unit="次" stacked />
    <Chart title="响应耗时趋势" trend={trend} series={latency} unit="ms" />
  </div>;
}
