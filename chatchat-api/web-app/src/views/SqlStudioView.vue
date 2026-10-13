<template>
  <section class="sql-studio sql-ide">
    <header class="sql-studio-bar sql-ide-titlebar">
      <div class="sql-studio-brand"><span>SQL</span><div><strong>SQL Studio</strong><small>工程开发工作台</small></div></div>
      <select aria-label="SQL 工程" :value="projectId" :disabled="busy" @change="selectProject($event.target.value)"><option value="" disabled>选择工程</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
      <button type="button" :disabled="busy" @click="newProjectOpen = true">＋ 新建工程</button>
      <span class="sql-ide-titlebar-spacer"></span>
      <label class="sql-dialect">方言<select v-model="dialect" aria-label="SQL 方言"><option v-for="option in dialects" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <button class="sql-primary" type="button" :disabled="busy || !activeFile?.editable || !!preview" @click="saveActive">保存文件</button>
    </header>
    <p v-if="error" class="sql-studio-notice error" role="alert">{{ error }}<button @click="error = ''" aria-label="关闭错误">×</button></p>
    <p v-if="message" class="sql-studio-notice" role="status">{{ message }}<button @click="message = ''" aria-label="关闭提示">×</button></p>
    <div class="sql-ide-shell" :style="ideStyle">
      <nav class="sql-activity-bar" aria-label="SQL Studio 面板">
        <button type="button" title="工程文件" aria-label="切换工程文件" :aria-pressed="layout.explorer" :class="{ active: layout.explorer }" @click="togglePanel('explorer')"><FolderTree :size="20" /></button>
        <button type="button" title="AI 工程助手" aria-label="切换 AI 工程助手" :aria-pressed="layout.assistant" :class="{ active: layout.assistant }" @click="togglePanel('assistant')"><Sparkles :size="20" /></button>
        <button type="button" title="问题和任务" aria-label="切换底部面板" :aria-pressed="layout.bottom" :class="{ active: layout.bottom }" @click="togglePanel('bottom')"><PanelBottom :size="20" /></button>
      </nav>
      <SqlProjectTree v-if="layout.explorer && projectId" :files="files" :references="references" :reference-ids="referenceIds" :project-name="projects.find(item => item.id === projectId)?.name" :active-id="activeId" :mapping-ids="mappingIds" :busy="busy"
        @open="openFile" @mapping="toggle(mappingIds, $event)" @reference="toggle(referenceIds, $event)" @reference-open="openReference" @upload="upload" @create="createFile" @refresh="action(refreshFiles)" @versions="showVersions" @download="download" @rename="renameFile" />
      <aside v-else-if="layout.explorer" class="sql-project-tree sql-ide-no-project"><strong>工程资源</strong><p>新建或选择工程后查看文件。</p></aside>
      <div v-if="layout.explorer" class="sql-panel-resizer" role="separator" aria-orientation="vertical" aria-label="调整工程文件宽度" @pointerdown="startResize('explorer', $event)" @dblclick="resetSize('explorer')"></div>
      <main class="sql-ide-main">
        <SqlEditorPane v-if="projectId" :project-id="projectId" :files="files" :open-ids="openIds" :active-id="activeId" :preview="preview" :dialect="dialect" :schema-columns="schemaColumns" :workspace-context="workspaceContext"
          @open="openFile" @close="closeFile" @edit="editFile" @save="saveActive" @versions="showVersions" @download="download" @preview-close="preview = null" @selection="useSelection" @mapping-reference="useMappingReference" @diagnostics="problemCount = $event" @cursor="cursor = $event" />
        <div v-else class="sql-ide-welcome"><div class="sql-ide-welcome-mark">SQL</div><h1>开始一个 SQL 工程</h1><p>将 Mapping、参考 SQL 与生成文件放在同一工程中，由现有 Agent Runtime 协助开发。</p><button class="sql-primary" @click="newProjectOpen = true">新建工程</button></div>
        <div v-if="layout.bottom" class="sql-bottom-resizer" role="separator" aria-orientation="horizontal" aria-label="调整底部面板高度" @pointerdown="startResize('bottom', $event)" @dblclick="resetSize('bottom')"></div>
        <SqlBottomPanel v-if="layout.bottom" :style="{ height: layout.bottomHeight + 'px' }" :tab="layout.bottomTab" :problem-count="problemCount" :error="error" :message="message" :result="result" :generations="generations" @tab="layout.bottomTab = $event" @close="layout.bottom = false" @generation="selectGeneration" />
        <button v-else-if="result || problemCount" type="button" class="sql-bottom-hint" @click="layout.bottom = true">{{ problemCount ? problemCount + ' 个问题' : '查看任务与输出' }}</button>
      </main>
      <div v-if="layout.assistant" class="sql-panel-resizer" role="separator" aria-orientation="vertical" aria-label="调整 AI 助手宽度" @pointerdown="startResize('assistant', $event)" @dblclick="resetSize('assistant')"></div>
      <SqlAssistantPanel v-if="layout.assistant && projectId" :mapping-count="mappingIds.length" :project-files="files" :references="references" :reference-ids="referenceIds" :models="models" :agents="agents" :agent-id="agentId" :model-name="modelName" :prompt="prompt" :messages="chatTurns" :busy="busy" :generations="generations" :generation-id="generationId" :result="result" :selected-code="selectedCode"
        @reference="toggle(referenceIds, $event)" @agent="agentId = $event" @model="modelName = $event" @prompt="prompt = $event" @send="generate" @generation="selectGeneration" @resume="resume" @cancel="cancel" @preview="preview = $event" @apply="apply" @clear-selection="selectedCode = null" @reference-file="addFileMention" />
      <aside v-else-if="layout.assistant" class="sql-assistant-panel sql-ide-no-project"><strong>AI 工程助手</strong><p>选择工程后开始协作。</p></aside>
      <footer class="sql-ide-status"><span>{{ projects.find(item => item.id === projectId)?.name || '未选择工程' }}</span><span>{{ dialects.find(item => item.value === dialect)?.label || dialect }}</span><span>{{ activeFile?.path || '未打开文件' }}</span><span class="sql-ide-status-spacer"></span><button type="button" @click="layout.bottom = true; layout.bottomTab = 'problems'">{{ problemCount }} 个问题</button><span v-if="cursor">Ln {{ cursor.line }}, Col {{ cursor.column }}</span><span>{{ result?.task?.status || 'Ready' }}</span></footer>
    </div>
    <Teleport to="body"><div v-if="newProjectOpen" class="sql-dialog-backdrop" @mousedown.self="newProjectOpen = false"><form class="sql-dialog" @submit.prevent="createProject"><h2>新建 SQL 工程</h2><label>工程名称<input v-model="projectName" maxlength="180" autofocus placeholder="例如：销售明细 ETL" /></label><footer><button type="button" @click="newProjectOpen = false">取消</button><button class="sql-primary" :disabled="busy || !projectName.trim()">创建工程</button></footer></form></div></Teleport>
    <Teleport to="body"><div v-if="versionFile" class="sql-dialog-backdrop" @mousedown.self="versionFile = null"><section class="sql-dialog sql-version-dialog"><h2>{{ versionFile.path }} · 保存版本</h2><p>历史版本只供查看，不覆盖当前编辑。</p><details v-for="version in versions" :key="version.version"><summary>v{{ version.version }}{{ version.sourceTaskId ? ' · Agent 产物' : ' · 用户保存' }}</summary><pre>{{ version.content }}</pre></details><footer><button @click="versionFile = null">关闭</button></footer></section></div></Teleport>
  </section>
</template>
<script src="../js/views/SqlStudioView.js"></script>
<style src="../styles/pages/sql-studio.css"></style>
<style src="../styles/pages/sql-studio-ide.css"></style>
