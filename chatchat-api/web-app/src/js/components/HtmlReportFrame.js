import {ref,watch,onBeforeUnmount} from 'vue';
import {loadHtmlReport} from '../utils/htmlReport';
export default {props:{report:{type:Object,required:true}},setup(props){
  const frame=ref(null),viewer=ref(null);const document=ref(''),loading=ref(false),error=ref('');let controller;
  watch(()=>props.report,async report=>{controller?.abort();const current=new AbortController();controller=current;document.value='';error.value='';loading.value=true;
    try{const html=await loadHtmlReport(report,current.signal);if(!current.signal.aborted)document.value=html;}
    catch(e){if(!current.signal.aborted)error.value=e.message||'报告暂时无法展示';}
    finally{if(!current.signal.aborted)loading.value=false;}
  },{immediate:true});
  const onMessage=event=>{if(event.source===frame.value?.contentWindow&&event.data?.type==='runtime-html-report-error')error.value='报告暂时无法正常展示，请重新加载或查看已归档文件。';};
  async function fullscreen(){try{await viewer.value?.requestFullscreen?.();}catch{/* The report remains available when fullscreen is declined. */}}
  function download(){const url=URL.createObjectURL(new Blob([document.value],{type:'text/html;charset=utf-8'}));const link=window.document.createElement('a');link.href=url;link.download=(props.report.entryPath?.split('/').pop()||'report.html');link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  window.addEventListener('message',onMessage);onBeforeUnmount(()=>{controller?.abort();window.removeEventListener('message',onMessage);});return {document,loading,error,frame,viewer,fullscreen,download};
}};
