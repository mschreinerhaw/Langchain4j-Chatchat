import {checkedPlotlyFigure} from './pythonVisualReport';

const scalar=value=>value==null||['number','string','boolean'].includes(typeof value);
const positive=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
const xy=trace=>['bar','scatter'].includes(trace.type||'scatter')&&trace.orientation!=='h'
  && Array.isArray(trace.x)&&Array.isArray(trace.y)&&trace.x.length===trace.y.length&&trace.x.every(scalar)&&trace.y.every(scalar);
export function explorationChartTypes(original){
  const figure=checkedPlotlyFigure(original),traces=figure.data;
  if(figure.frames?.length)return [];
  if(traces.every(xy)){
    const types=['bar','line','scatter'];
    if(traces.length===1&&traces[0].x.length&&traces[0].y.every(positive)&&traces[0].y.some(v=>v>0)
      &&new Set(traces[0].x.map(String)).size===traces[0].x.length)types.push('pie');
    return types;
  }
  if(traces.length===1&&traces[0].type==='pie'&&Array.isArray(traces[0].labels)&&Array.isArray(traces[0].values)
    &&traces[0].labels.length===traces[0].values.length&&traces[0].labels.every(scalar)&&traces[0].values.every(positive))return ['bar','pie'];
  return [];
}

// Only a view of the same points. No aggregation, inferred observations or source mutation.
export function explorationFigure(original,type){
  const figure=checkedPlotlyFigure(original);
  if(!type)return figure;
  if(!['bar','line','scatter','pie'].includes(type))throw new Error('不支持的图表类型');
  if(!explorationChartTypes(figure).includes(type))throw new Error(type==='pie'
    ?'饼图需要一条非负的类别数列，当前图表不能直接转换。'
    :'此图表没有可切换的纵向 X/Y 数列，请保留原图。');
  figure.data=figure.data.map(trace=>{
    if(type==='pie')return {type:'pie',name:trace.name||'',labels:trace.x||trace.labels,values:trace.y||trace.values,sort:false};
    const result={...trace,x:trace.x||trace.labels,y:trace.y||trace.values,type:type==='bar'?'bar':'scatter'};
    for(const key of ['orientation','labels','values','hole','pull','sort','textinfo','direction','hovertemplate'])delete result[key];
    if(type==='line'||type==='scatter'){
      result.mode=type==='line'?'lines+markers':'markers';
      for(const key of ['width','base','offset'])delete result[key];
    }else for(const key of ['mode','line','fill','stackgroup'])delete result[key];
    return result;
  });
  return checkedPlotlyFigure(figure);
}

export function explorationChartStyle(original){
  const figure=checkedPlotlyFigure(original),layout=figure.layout||(figure.layout={});
  delete layout.title;
  delete layout.width;
  delete layout.height;
  layout.autosize=true;
  layout.font={...layout.font,family:'Arial, "Noto Sans SC", sans-serif',size:12,color:'#44546b'};
  layout.margin={l:72,r:24,t:24,b:92};
  layout.legend={...layout.legend,orientation:'h',x:0,y:-.22};
  layout.uirevision='exploration-view';
  const title=typeof layout.xaxis?.title==='object'?layout.xaxis.title.text:layout.xaxis?.title;
  const values=figure.data[0]?.x;
  if(/日期|date/i.test(title||'')&&Array.isArray(values)&&values.length&&values.length<=48&&values.every(v=>/^\d{8}$|^\d{4}-\d{2}-\d{2}$/.test(String(v)))){
    layout.xaxis={...layout.xaxis,tickmode:'array',tickvals:values,ticktext:values.map(v=>String(v).replace(/^(\d{4})(\d{2})(\d{2})$/,'$1-$2-$3')),tickangle:0};
  }
  return figure;
}

export function explorationChartTable(original){
  const figure=checkedPlotlyFigure(original),traces=figure.data,limit=10000;
  if(traces.every(xy)&&traces.every(t=>JSON.stringify(t.x)===JSON.stringify(traces[0].x))){
    const title=figure.layout?.xaxis?.title;
    return {columns:[typeof title==='object'?title.text||'类别':title||'类别',...traces.map((t,i)=>t.name||`数列 ${i+1}`)],
      rows:traces[0].x.slice(0,limit).map((x,i)=>[x,...traces.map(t=>t.y[i])]),truncated:traces[0].x.length>limit};
  }
  const rows=[];let count=0;
  for(const [index,t] of traces.entries()){
    const labels=t.x||t.labels,values=t.y||t.values;
    if(!Array.isArray(labels)||!Array.isArray(values)||labels.length!==values.length||!labels.every(scalar)||!values.every(scalar))continue;
    count+=values.length;
    for(let i=0;i<values.length&&rows.length<limit;i++)rows.push([t.name||`数列 ${index+1}`,labels[i],values[i]]);
  }
  return rows.length?{columns:['数列','类别','数值'],rows,truncated:count>rows.length}:null;
}
