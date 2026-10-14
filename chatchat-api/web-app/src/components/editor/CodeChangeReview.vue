<template>
  <Teleport to="body">
    <div class="code-review-backdrop" @keydown.esc.stop.prevent="$emit('close')">
      <section ref="dialog" class="code-review-dialog" role="dialog" aria-modal="true" aria-label="审查代码变更" tabindex="-1">
        <header><div><strong>审查代码变更</strong><small>{{ title }} · {{ files.length }} 个文件</small></div><button type="button" aria-label="关闭变更对比" @click="$emit('close')"><X :size="18" /></button></header>
        <nav aria-label="变更文件"><button v-for="(file,index) in files" :key="file.path" type="button" :class="{active:index===selected}" :aria-pressed="index===selected" @click="selected=index">{{ file.isNew ? '新增' : '修改' }} · {{ file.path }}</button></nav>
        <div class="code-review-toolbar"><span class="review-added">+{{ summary.additions }} 行新增</span><span class="review-deleted">−{{ summary.deletions }} 行删除</span><span v-if="!summary.changed">没有代码差异</span><div><button type="button" :aria-pressed="!sideBySide" @click="sideBySide=!sideBySide">{{ sideBySide ? '统一视图' : '并排视图' }}</button><button type="button" aria-label="上一处变更" @click="navigate(-1)"><ChevronUp :size="14" />上一处</button><button type="button" aria-label="下一处变更" @click="navigate(1)"><ChevronDown :size="14" />下一处</button></div></div>
        <div class="code-review-labels"><span>修改前{{ current?.isNew ? ' · 文件尚不存在' : '' }}</span><span>修改后 · AI 建议</span></div>
        <div ref="host" class="code-review-editor" aria-label="代码差异编辑器"></div>
        <footer><p role="status">{{ blockedReason || hint }}</p><button type="button" @click="$emit('close')">返回</button><button type="button" class="code-review-accept" :disabled="busy || !!blockedReason || !hasChanges" @click="$emit('apply')">{{ busy ? '正在应用…' : applyLabel }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>
<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { X, ChevronUp, ChevronDown } from '@lucide/vue';
import { buildPythonLineDiff } from '../../js/utils/pythonCodeDiff';
const props=defineProps({ files:{type:Array,required:true}, language:{type:String,default:'python'}, title:String,
  initialPath:String, blockedReason:String, busy:Boolean, applyLabel:{type:String,default:'应用变更'},
  hint:{type:String,default:'绿色为新增，红色为删除。确认后写入编辑器，可通过撤销恢复；保存后才会持久化。'} });
defineEmits(['close','apply']);
const selected=ref(Math.max(0,props.files.findIndex(file=>file.path===props.initialPath))),sideBySide=ref(true),host=ref(null),dialog=ref(null);
const current=computed(()=>props.files[selected.value] || props.files[0]);
const summary=computed(()=>buildPythonLineDiff(current.value?.before || '',current.value?.after || ''));
const hasChanges=computed(()=>props.files.some(file=>file.before!==file.after));
let editor,original,modified,previousFocus,changes,changeIndex=-1,app,previousInert;
function showFile() {
  if(!editor || !current.value)return;
  editor.setModel(null);original?.dispose();modified?.dispose();
  original=monaco.editor.createModel(current.value.before || '',props.language);
  modified=monaco.editor.createModel(current.value.after || '',props.language);
  editor.setModel({original,modified});changeIndex=-1;
}
function navigate(direction) {
  const items=editor?.getLineChanges() || [];
  if(!items.length)return;
  changeIndex=changeIndex<0 ? (direction>0 ? 0 : items.length-1) : (changeIndex+direction+items.length)%items.length;
  editor.getModifiedEditor().revealLineInCenter(Math.max(1,items[changeIndex].modifiedStartLineNumber));
  editor.getOriginalEditor().revealLineInCenter(Math.max(1,items[changeIndex].originalStartLineNumber));
}
function trapFocus(event) {
  if(event.key!=='Tab')return;
  const nodes=[...dialog.value.querySelectorAll('button:not(:disabled),[tabindex="0"],textarea')].filter(el=>el.getClientRects().length);
  const first=nodes[0],last=nodes.at(-1);
  if(event.shiftKey && (document.activeElement===first || document.activeElement===dialog.value)){event.preventDefault();last?.focus();}
  else if(!event.shiftKey && document.activeElement===last){event.preventDefault();first?.focus();}
}
watch(current,()=>nextTick(showFile));
watch(sideBySide,value=>editor?.updateOptions({renderSideBySide:value}));
onMounted(()=>{
  app=document.getElementById('app');previousInert=app?.inert;if(app)app.inert=true;
  previousFocus=document.activeElement;dialog.value.focus();dialog.value.addEventListener('keydown',trapFocus);
  editor=monaco.editor.createDiffEditor(host.value,{automaticLayout:true,readOnly:true,originalEditable:false,renderSideBySide:true,
    theme:'vs',fontSize:14,scrollBeyondLastLine:false,renderIndicators:true,renderGutterMenu:false,renderMarginRevertIcon:false,
    occurrencesHighlight:'off',ignoreTrimWhitespace:false,minimap:{enabled:false}});
  changes=editor.onDidUpdateDiff(()=>{changeIndex=-1;});showFile();
});
onBeforeUnmount(()=>{dialog.value?.removeEventListener('keydown',trapFocus);changes?.dispose();editor?.dispose();original?.dispose();modified?.dispose();if(app)app.inert=previousInert;previousFocus?.isConnected && previousFocus.focus();});
</script>
<style>
.code-review-backdrop{position:fixed;inset:0;z-index:2000;background:#18294270;display:flex;align-items:center;justify-content:center;padding:24px}
.code-review-dialog{width:min(1480px,96vw);height:min(900px,92vh);display:flex;flex-direction:column;min-width:0;background:#fff;border:1px solid #cfdbef;border-radius:12px;box-shadow:0 20px 80px #14233e40;overflow:hidden;color:#26364d;outline:none}
.code-review-dialog button{display:inline-flex;align-items:center;justify-content:center;gap:5px;flex-shrink:0;border:1px solid #d5dfef;background:#fff;color:#405b80;border-radius:6px;padding:6px 10px;font-size:12px;cursor:pointer}
.code-review-dialog button:hover:not(:disabled){background:#edf3ff;color:#1d4ed8}.code-review-dialog button:focus-visible{outline:2px solid #3979ee;outline-offset:2px}.code-review-dialog button:disabled{opacity:.5;cursor:not-allowed}
.code-review-dialog>header{display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid #e3eaf5}.code-review-dialog>header>div{display:grid;gap:4px}.code-review-dialog small{font-size:12px;color:#7a8ca6}
.code-review-dialog>nav{display:flex;gap:6px;overflow:auto;padding:10px 16px}.code-review-dialog>nav .active{background:#edf3ff;border-color:#8aaff6;color:#225ed0}
.code-review-toolbar{display:flex;align-items:center;gap:12px;padding:4px 16px 10px;font-size:12px}.code-review-toolbar>div{margin-left:auto;display:flex;gap:6px}.review-added{color:#15803d}.review-deleted{color:#b91c1c}
.code-review-labels{display:grid;grid-template-columns:1fr 1fr;background:#f2f6fc;border-block:1px solid #dce5f3;font-size:12px;color:#607491}.code-review-labels span{padding:8px 20px}.code-review-editor{flex:1;min-height:100px;min-width:0}
.code-review-dialog>footer{display:flex;align-items:center;gap:10px;padding:12px 16px;border-top:1px solid #dce5f3}.code-review-dialog>footer p{margin:0 auto 0 0;font-size:12px;line-height:1.5;color:#71839b}.code-review-dialog .code-review-accept{background:#2563eb;border-color:#2563eb;color:#fff}.code-review-dialog .code-review-accept:hover:not(:disabled){background:#1d4ed8;color:#fff}
@media(max-width:700px){.code-review-backdrop{padding:8px}.code-review-toolbar{flex-wrap:wrap}.code-review-toolbar>div{margin-left:0}.code-review-dialog>footer{flex-wrap:wrap}.code-review-dialog>footer p{width:100%}}
</style>
