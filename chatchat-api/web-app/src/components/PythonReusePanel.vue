<template>
  <details class="python-reuse-panel" @toggle="opened">
    <summary>复用已有 Python 代码进行分析</summary>
    <p>引用代码只读取指定版本。确认执行后才会进入隔离环境；修改以差异提案呈现，原文件不会自动覆盖。编辑器未保存的内容请先保存，或作为代码片段引用。</p>
    <fieldset :disabled="running">
      <legend>代码与数据</legend>
      <label v-for="script in candidateScripts" :key="script.id"><input v-model="selected" type="checkbox" :value="script.id" />{{ scriptPath(script) }} · v{{ script.currentVersion }}</label>
      <label>上传 Python 文件（保留目录关系）<input type="file" accept=".py" multiple @change="upload" /></label>
      <label>上传多文件工程<input type="file" webkitdirectory multiple @change="upload" /></label>
      <ul><li v-for="file in uploaded" :key="file.id">{{ file.path }} <button type="button" @click="uploaded=uploaded.filter(f=>f.id!==file.id)">移除</button></li></ul>
      <label>补充代码片段文件名<input v-model="snippetPath" placeholder="helpers.py" /></label>
      <label>代码片段<textarea v-model="snippet" rows="3" placeholder="可选，作为独立源码文件引用" /></label>
      <label>入口文件相对路径<input v-model="entryPath" placeholder="main.py" /></label>
      <label>分析需求<textarea v-model="instruction" rows="3" placeholder="说明需复用的分析逻辑、预期结果及验收条件" /></label>
      <label>授权数据文件（可多选）<select v-model="dataIds" multiple><option v-for="file in dataFiles" :key="file.id" :value="file.id">{{ file.fileName }}</option></select></label>
      <button type="button" :disabled="preparing" @click="prepare">{{ preparing?'正在校验引用…':'加载并冻结代码引用' }}</button>
    </fieldset>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="state">
      <p v-if="state.instruction">本任务模型：{{ state.modelName||'系统默认模型' }}。已冻结需求：{{ state.instruction }}。调整输入后请重新加载引用再执行。</p>
      <h4>已加载的代码</h4>
      <ul><li v-for="file in state.sources" :key="file.path">{{ file.path }} · {{ file.kind }} v{{ file.version }} · SHA256 {{ file.sha256 }}</li></ul>
      <p>复用能力由 Cline 根据源码判断；以下代码差异和执行回执用于核对实际结果。</p>
      <label><input v-model="consent" type="checkbox" :disabled="executing" />我授权在当前默认隔离环境执行本次代码快照及所选数据，允许在既有预算内修复并重新执行</label>
      <button type="button" :disabled="!canExecute" @click="execute">{{ state.outcome||state.status==='PREPARING'?'继续原任务（不重置预算）':'确认并执行分析' }}</button>
      <button v-if="running && state.outcome" type="button" @click="cancel">请求取消</button><button type="button" @click="refresh">刷新真实状态</button>
      <p role="status"><span v-if="running" class="python-reuse-spinner" />{{ state.status }} · 已归档执行回执 {{ executions.length }} 条 · 成功 {{ executions.filter(e=>e.status==='SUCCEEDED'&&e.resultAvailable).length }} 条 · 失败 {{ executions.filter(e=>e.status!=='SUCCEEDED'||!e.resultAvailable).length }} 条</p>
      <ol class="python-reuse-events"><li v-for="event in progressEvents" :key="event.eventId">{{ event.occurredAt }} · {{ progressLabel(event) }}</li></ol>
      <details v-for="execution in executions" :key="execution.executionId"><summary>{{ execution.script?.scriptVersion ? `v${execution.script.scriptVersion}` : execution.executionId }} · {{ execution.status }} · {{ execution.error?.diagnosticCode || execution.outputStatus }} · {{ execution.durationMs }} ms</summary><pre>{{ execution }}</pre><p>原始日志不可用时，仅展示脱敏诊断，不补造 Traceback。</p></details>
      <section v-if="state.resultAvailable"><h4>业务分析成果</h4><ReportMarkdown v-for="(row,index) in state.results||[]" :key="index" :content="row.reportMarkdown||''" :render-markdown="renderMarkdown" /><p>结构校验与业务正确性分开：业务独立校验状态 {{ state.businessValidation||'NOT_EVALUATED' }}</p></section>
      <section v-if="versions.length"><h4>代码成果与版本差异</h4><label>执行代码版本<select v-model="operation" @change="followLatest=false"><option v-for="version in versions" :key="version.operationId" :value="version.operationId">{{ version.script?.scriptVersion||version.operationId }} · {{ version.sha256 }}</option></select></label>
        <article v-for="file in diffs" :key="file.path"><label><input v-model="applyPaths" type="checkbox" :value="file.path" :disabled="(!file.diff.changed&&!file.importable)||running" />{{ file.path }} {{ file.diff.changed?'有变更':file.importable?'原代码完整保留（可导入）':'原代码完整保留' }}</label><PythonCodeDiff v-if="file.diff.changed" :diff="file.diff" :scope="file.path" /></article>
        <button type="button" :disabled="running||!applyPaths.length" @click="apply">确认差异并回写选中文件</button><p>回写会锁定并核对原文件版本；新增文件写入当前选中的 Python 环境。失败或冲突时不覆盖原工程。</p>
      </section>
      <section v-if="state.applied?.length"><h4>已确认的回写版本</h4><ul><template v-for="receipt in state.applied" :key="receipt.receiptId"><li v-for="file in receipt.saved" :key="file.scriptId+':'+file.version">{{ file.path }} · v{{ file.version }} · {{ file.sha256 }} · {{ receipt.confirmedAt }}</li></template></ul></section>
    </template>
  </details>
</template>
<script src="../js/components/PythonReusePanel.js"></script>
<style scoped src="../styles/components/python-reuse-panel.css"></style>
