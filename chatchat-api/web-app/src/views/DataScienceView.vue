<template>
  <section class="ds-page" :class="{'develop-mode':tab==='develop'}">
    <header class="ds-header">
      <div><span>能力管理 / 数据科学</span><h1>Python 数据科学工作台</h1></div>
      <button class="ds-primary" @click="openAssetDialog">＋ 发布 Python Asset</button>
    </header>
    <nav class="ds-tabs"><button v-for="item in tabs" :key="item.id" :class="{active:tab===item.id}" @click="tab=item.id">{{ item.label }}</button></nav>
    <p v-if="error" class="ds-alert error">{{ error }}</p><p v-if="message" class="ds-alert success">{{ message }}</p>

    <div v-if="loading" class="ds-empty">正在加载数据科学工作台…</div>
    <section v-show="!loading&&tab==='environment'" class="ds-environment-pane">
      <div v-if="!assets.length" class="ds-empty"><strong>当前没有可用的数据科学环境</strong><p>请先发布 Python Asset。只有 READY 环境才允许创建、运行和发布脚本。</p><button class="ds-primary" @click="openAssetDialog">发布环境</button></div>
      <div v-else class="ds-card-grid"><article v-for="asset in assets" :key="asset.id" class="ds-card ds-environment-card"><div class="ds-card-head"><div><span class="environment-eyebrow">数据科学环境</span><strong>{{ asset.name }}</strong></div><span :class="['ds-status', statusClass(asset.status)]">{{ assetStatusLabel(asset.status) }}</span></div><p>{{ asset.description || '用于 Python 数据分析、脚本调试与能力发布。' }}</p><div class="environment-capabilities"><span>Python {{ asset.pythonVersion || '3.11' }}</span><span>独立运行环境</span><span>支持开发、测试与发布</span></div><footer><span :class="['environment-dot',statusClass(asset.status)]"></span>{{ environmentStatusMessage(asset.status) }}</footer></article></div>
    </section>

    <div v-show="!loading&&tab==='develop'" class="ds-develop-pane">
      <div v-if="!readyAssets.length" class="ds-empty"><strong>开发入口已锁定</strong><p>当前用户没有 READY 状态的 Python Asset，请先发布并成功创建隔离环境。</p><button class="ds-primary" @click="tab='environment'">前往 Python 环境</button></div>
      <div v-else ref="workspace" class="ds-ide" :class="{'ai-collapsed':!aiOpen, fullscreen:isFullscreen}">
        <header class="ide-commandbar">
          <div class="ide-brand"><span class="python-mark">Py</span><div><strong>Python Studio</strong><small>安全隔离开发环境</small></div></div>
          <div class="ide-context"><label><span>运行环境</span><select v-model="form.assetId"><option v-for="asset in readyAssets" :value="asset.id" :key="asset.id">{{ asset.name }}</option></select></label><span class="ide-runtime"><i></i> Docker Ready</span></div>
          <div class="ide-actions"><button title="Ctrl/Cmd + S" @click="save" :disabled="busy"><span>⌘S</span> 保存</button><button title="Ctrl/Cmd + Enter" @click="run" :disabled="busy||!form.id" class="run">{{ runState==='running'?'◌ 执行中…':'▶ 运行' }}</button><button class="publish" @click="publishOpen=true" :disabled="busy||!canPublish">发布能力</button><button class="icon-button" title="AI 助手" @click="aiOpen=!aiOpen">✦</button><button class="expand-button" :title="isFullscreen?'退出沉浸编辑':'放大为沉浸编辑器'" @click="toggleFullscreen">{{ isFullscreen?'↙ 退出全屏':'⛶ 放大编辑' }}</button></div>
        </header>

        <aside class="ide-explorer">
          <div class="panel-heading"><div><small>WORKSPACE</small><strong>我的脚本</strong></div><button title="新建脚本" @click="newScript">＋</button></div>
          <div class="explorer-search"><span>⌕</span><input v-model="explorerQuery" placeholder="搜索脚本"></div>
          <div class="explorer-group"><span>⌄</span><strong>PYTHON ASSET</strong><small>{{ scripts.length }}</small></div>
          <div class="explorer-files">
            <button v-for="script in filteredScripts" :key="script.id" :class="{active:form.id===script.id}" @click="selectScript(script)"><span class="py-file">Py</span><span><strong>{{ script.fileName }}</strong><small>{{ script.title || '未命名脚本' }}</small></span><i :class="statusClass(script.status)"></i></button>
            <div v-if="!filteredScripts.length" class="explorer-empty">没有匹配的脚本</div>
          </div>
          <footer><span>◉ {{ readyAssets.length }} 个环境</span><button @click="load">↻</button></footer>
        </aside>

        <main class="ide-main">
          <div class="editor-tabs"><div class="active"><span class="py-file">Py</span><input v-model.trim="form.fileName" placeholder="analysis.py"><i v-if="dirty" title="有未保存修改"></i><button v-if="dirty" title="还原" @click="editor.setValue(savedSource)">×</button></div><button class="new-tab" @click="newScript">＋</button><div class="editor-tools"><button @click="formatDocument">格式化</button><button @click="aiOpen=true">✦ AI 补全 <kbd>⌘K</kbd></button></div></div>
          <div class="editor-breadcrumb"><span>workspace</span><b>›</b><span>{{ form.fileName }}</span><b>›</b><strong>Python</strong></div>
          <div ref="codeEditor" class="monaco-host" aria-label="Python 脚本编辑器"></div>
          <section class="ide-bottom">
            <nav><button :class="{active:bottomTab==='console'}" @click="bottomTab='console'">运行日志</button><button :class="{active:bottomTab==='parameters'}" @click="bottomTab='parameters'">运行参数</button><span></span><button title="清空" @click="clearConsole">清空</button></nav>
            <div v-if="bottomTab==='console'" class="terminal"><div><b>Python Runtime</b><span :class="['run-state',runState]"><i></i>{{ runStateLabel }}</span><small v-if="runFeedback?.durationMs!=null">{{ runFeedback.durationMs }} ms</small><small v-if="runFeedback?.exitCode!=null">退出码 {{ runFeedback.exitCode }}</small></div><pre :class="{failed:runState==='failed'}">{{ consoleText || '$ 点击“运行”执行当前脚本，stdout / stderr 和执行状态会显示在这里。' }}</pre></div>
            <div v-else class="parameter-editor"><label>Agent 输入参数 <span>JSON</span></label><textarea v-model="parametersText" spellcheck="false"></textarea></div>
          </section>
          <footer class="ide-statusbar"><span>⑂ main*</span><span>✓ 隔离策略已启用</span><i></i><span>Ln {{ cursorPosition.lineNumber }}, Col {{ cursorPosition.column }}</span><span>Spaces: 4</span><span>UTF-8</span><span>Python 3.11</span><span>{{ codeLines }} 行 · {{ codeChars }} 字符</span></footer>
        </main>

        <aside class="ai-panel">
          <header><div><span class="ai-orb">✦</span><div><strong>AI 编程助手</strong><small :title="selectedAiModelLabel">{{ aiBusy?'正在调用 '+selectedAiModelLabel:aiStage==='ready'?'代码建议已生成':aiStage==='applied'?'建议已写入编辑器':selectedAiModelLabel }}</small></div></div><button @click="aiOpen=false">×</button></header>
          <section class="ai-body">
            <div v-if="aiBusy" class="ai-progress"><span class="ai-thinking">✦</span><strong>正在生成 Python 代码</strong><p>{{ selectedAiModelLabel }} · 已读取当前脚本 {{ codeLines }} 行{{ aiSelection?'及选中代码':'' }}</p><ol><li :class="{done:aiProgressStep>1,active:aiProgressStep===1}"><i></i>读取代码上下文</li><li :class="{done:aiProgressStep>2,active:aiProgressStep===2}"><i></i>分析提示词意图</li><li :class="{done:aiProgressStep>3,active:aiProgressStep===3}"><i></i>调用所选模型生成代码</li><li :class="{done:aiProgressStep===4}"><i></i>等待结果返回</li></ol></div>
            <template v-else-if="!aiSuggestion"><div class="ai-intro"><span>✦</span><strong>想让代码做什么？</strong><p>描述需求，AI 会结合当前脚本和选中代码生成建议。</p></div><div class="ai-examples"><button v-for="example in aiExamples" :key="example" @click="useAiExample(example)">{{ example }}</button></div></template>
            <div v-else class="ai-result" :class="{applied:aiStage==='applied'}"><div><strong>{{ aiStage==='applied'?'代码已写入':'代码建议已生成' }}</strong><span :title="aiSuggestion.modelName">{{ aiSuggestion.modelName || selectedAiModelLabel }} · {{ aiSuggestionLines }} 行 · {{ aiElapsedMs }} ms</span></div><p v-if="aiAppliedInfo" class="ai-applied-notice"><b>✓ 已{{ aiAppliedInfo.verb }}{{ aiAppliedInfo.mode }}</b><span>{{ aiAppliedInfo.lines }} 行代码已写入并选中，当前修改尚未保存。</span></p><pre>{{ aiSuggestion.code }}</pre><footer><button @click="resetAiSuggestion">{{ aiStage==='applied'?'继续生成':'放弃' }}</button><button class="apply" :class="{done:aiStage==='applied'}" :disabled="aiStage==='applied'" @click="applyAiSuggestion">{{ aiStage==='applied'?'✓ 已应用':aiApplyLabel }}</button></footer></div>
          </section>
          <footer class="ai-composer"><div class="ai-composer-options"><select v-model="aiAction" :disabled="aiBusy"><option value="generate">生成</option><option value="continue">续写</option><option value="fix">修复</option><option value="optimize">优化</option></select><select v-model="aiModel" class="ai-model-select" :disabled="aiBusy||!aiModels.length" title="代码生成模型"><option v-if="!aiModels.length" value="">暂无可用模型</option><option v-for="model in aiModels" :key="model.value" :value="model.value">{{ model.label }}{{ model.defaultModel?'（默认）':'' }}</option></select></div><textarea ref="aiPrompt" v-model.trim="aiPrompt" :disabled="aiBusy" placeholder="例如：读取 values 数组，计算均值、中位数和标准差…" @keydown.ctrl.enter.prevent="askAi" @keydown.meta.enter.prevent="askAi"></textarea><div><small>{{ aiBusy?'AI 正在读取并生成代码…':'Ctrl + Enter 发送' }}</small><button @click="askAi" :disabled="aiBusy||!aiPrompt||!aiModel">{{ aiBusy?'生成中…':'发送 ↑' }}</button></div></footer>
        </aside>
      </div>
    </div>

    <div v-show="!loading&&tab==='scripts'" class="ds-scripts-pane"><div v-if="!scripts.length" class="ds-empty">暂无脚本</div><div v-else class="ds-table-wrap"><table><thead><tr><th>脚本</th><th>Asset</th><th>版本</th><th>状态</th><th>最后测试</th><th>更新时间</th></tr></thead><tbody><tr v-for="script in scripts" :key="script.id" @dblclick="selectScript(script)"><td><strong>{{ script.fileName }}</strong><small>{{ script.title }}</small></td><td>{{ assetName(script.assetId) }}</td><td>v{{ script.currentVersion }}</td><td><span :class="['ds-status',statusClass(script.status)]">{{ script.status }}</span></td><td>{{ script.lastTestSucceeded ? '成功' : '未通过' }}</td><td>{{ formatTime(script.updatedAt) }}</td></tr></tbody></table></div></div>

    <div v-if="assetOpen" class="ds-modal" @mousedown.self="assetOpen=false"><form @submit.prevent="createAsset"><h2>发布 Python Asset 环境</h2><p>只能选择 MCP 管理端已发布的环境；镜像和资源边界由 MCP 统一治理。</p><label>环境名称 *<input v-model="assetForm.name" required></label><label>环境描述<textarea v-model="assetForm.description"></textarea></label><label>MCP 已发布环境 *<select v-model="assetForm.environmentId" required><option disabled value="">请选择环境</option><option v-for="env in environmentCatalog" :key="env.id" :value="env.id">{{ env.name }} · Python {{ env.pythonVersion }} · v{{ env.versionNumber }}</option></select></label><div v-if="selectedEnvironment" class="ds-alert"><strong>{{ selectedEnvironment.dockerImage }}</strong><br>{{ selectedEnvironment.cpuLimit }} CPU / {{ selectedEnvironment.memoryLimit }} / 网络{{ selectedEnvironment.networkEnabled?'开启':'禁用' }} / 超时 {{ selectedEnvironment.timeoutSeconds }}s</div><p v-if="!environmentCatalog.length" class="ds-alert error">MCP 尚未发布 Python 环境，请先到 MCP 管理端的 Python 管理页面发布。</p><footer><button type="button" @click="assetOpen=false">取消</button><button class="ds-primary" :disabled="busy||!environmentCatalog.length">{{ busy?'创建中…':'发布环境' }}</button></footer></form></div>
    <div v-if="publishOpen" class="ds-modal" @mousedown.self="publishOpen=false"><form @submit.prevent="publish"><h2>发布 Python 模板</h2><p>场景描述将同时参与 BM25 与向量检索，发布后源码以当前版本快照执行。</p><label>模板名称 *<input v-model="publishForm.templateName" required></label><label>场景描述 *<textarea v-model="publishForm.scenario" required placeholder="说明适用业务场景和用户可能提出的问题"></textarea></label><label>功能描述 *<textarea v-model="publishForm.description" required></textarea></label><div class="ds-form-row"><label>关键词<input v-model="publishForm.keywords"></label><label>所属领域<input v-model="publishForm.domain"></label></div><div class="ds-form-row"><label>输入 Schema<textarea v-model="publishForm.inputSchema"></textarea></label><label>输出 Schema<textarea v-model="publishForm.outputSchema"></textarea></label></div><footer><button type="button" @click="publishOpen=false">取消</button><button class="ds-primary" :disabled="busy">发布到 MCP</button></footer></form></div>
  </section>
</template>
<script src="../js/views/DataScienceView.js"></script>
<style src="../styles/pages/data-science.css"></style>
