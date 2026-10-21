import {getStoredAuthSession} from '../../services/api';
import {workspaceHeaders} from './workspaceContext';

export async function readReportArtifact(asset,signal){
  if(!/^[A-Za-z0-9_-]{1,100}$/.test(asset?.fileId||'') || !(asset.size>0&&asset.size<=8*1024*1024))throw new Error('图表文件引用无效');
  const auth=getStoredAuthSession();
  const response=await fetch(`/api/v1/interactions/user-assets/${encodeURIComponent(asset.fileId)}/content`,{signal,headers:{...(auth?.token?{Authorization:`Bearer ${auth.token}`} : {}),...workspaceHeaders()}});
  if(!response.ok)throw new Error(response.status===403||response.status===404?'无权访问此图表或文件已移除':`图表读取失败（${response.status}）`);
  const bytes=await response.arrayBuffer();if(bytes.byteLength!==asset.size)throw new Error('图表文件大小与已校验产物不一致');
  return bytes;
}

// Defense in depth for historical/fixture resources. No model HTML, URLs, functions or external renderers.
export function checkedPlotlyFigure(figure){
  if(!figure || !Array.isArray(figure.data)||!figure.data.length||figure.data.length>128)throw new Error('交互图缺少有效数据');
  const forbidden=new Set(['__proto__','prototype','constructor','source','src','href','url','accesstoken','mapbox','images','bdata']);let nodes=0;
  const visit=(value,depth=0)=>{
    if(depth>=40||++nodes>300000)throw new Error('交互图超过复杂度限制');
    if(Array.isArray(value)){if(value.length>100000)throw new Error('交互图数据点过多');value.forEach(item=>visit(item,depth+1));}
    else if(value&&typeof value==='object')for(const [key,item] of Object.entries(value)){if(forbidden.has(key.toLowerCase()))throw new Error('交互图包含外部资源或不安全配置');visit(item,depth+1);}
    else if(typeof value==='string'){
      const plain=value.replace(/<\/?(?:b|i|em|strong|sup|sub|extra)>|<br\s*\/?>/gi,'');
      if(value.length>20000||plain.includes('<')||/(?:https?:|javascript:|data:|file:|\/\/)/i.test(plain))throw new Error('交互图包含不安全文本');
    }else if(value!=null&&typeof value!=='boolean'&&!(typeof value==='number'&&Number.isFinite(value)))throw new Error('交互图数值无效');
  };
  for(const trace of figure.data)if(['image','scattergeo','choropleth','scattermapbox','choroplethmapbox','scattermap','choroplethmap'].includes(trace.type))throw new Error('交互图不允许联网地图或外部图像');
  visit(figure);return JSON.parse(JSON.stringify({data:figure.data,layout:figure.layout||{},frames:figure.frames||[]}));
}

export function plotlyFrameDocument(runtimeUrl,origin){
  const url=new URL(runtimeUrl,origin);if(url.origin!==new URL(origin).origin)throw new Error('绘图组件必须来自本站');
  const escaped=url.href.replace(/&/g,'&amp;').replace(/"/g,'&quot;');
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src ${url.origin} 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>html,body,#plot{margin:0;width:100%;height:100%;overflow:hidden}body{font-family:sans-serif}</style></head><body><div id="plot"></div><script src="${escaped}"></script><script>addEventListener('message',async event=>{if(event.source!==parent||event.data?.type!=='runtime-plotly-figure')return;try{const figure=event.data.figure;await Plotly.newPlot('plot',figure.data,{...figure.layout,autosize:true},{responsive:true,displaylogo:false,showLink:false,scrollZoom:true,modeBarButtonsToRemove:['sendDataToCloud']});if(figure.frames?.length)await Plotly.addFrames('plot',figure.frames);parent.postMessage({type:'runtime-plotly-rendered'},'*')}catch(error){document.getElementById('plot').textContent='交互图渲染失败';parent.postMessage({type:'runtime-plotly-failed'},'*')}});</script></body></html>`;
}
