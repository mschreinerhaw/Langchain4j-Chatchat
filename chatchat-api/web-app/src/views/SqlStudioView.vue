<template>
  <section class="sql-studio sql-ide">
    <header class="sql-studio-bar sql-ide-titlebar">
      <div class="sql-studio-brand"><FileCode2 :size="22" /><strong>SQL Studio</strong></div>
      <select aria-label="SQL 工程" :value="projectId" :disabled="busy" @change="selectProject($event.target.value)"><option value="" disabled>选择工程</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
      <button type="button" :disabled="busy || !projectId" @click="openNewFile"><Plus :size="15" />新建文件</button>
      <button type="button" :disabled="busy" @click="newProjectOpen = true"><Plus :size="15" />新建工程</button>
      <button type="button" class="sql-delete-project" :disabled="busy || !projectId" @click="deleteProject">删除工程</button>
      <nav class="sql-activity-bar" aria-label="SQL Studio 面板">
        <button type="button" title="工程文件" aria-label="切换工程文件" :aria-pressed="layout.explorer" :class="{ active: layout.explorer }" @click="togglePanel('explorer')"><FolderTree :size="18" /></button>
        <button type="button" title="AI 工程助手" aria-label="切换 AI 工程助手" :aria-pressed="layout.assistant" :class="{ active: layout.assistant }" @click="togglePanel('assistant')"><Sparkles :size="18" /></button>
        <button type="button" title="问题和任务" aria-label="切换底部面板" :aria-pressed="layout.bottom" :class="{ active: layout.bottom }" @click="togglePanel('bottom')"><PanelBottom :size="18" /></button>
      </nav>
      <span class="sql-ide-titlebar-spacer"></span>
      <button type="button" :disabled="busy || activeFile?.kind !== 'SQL' || !activeFile?.editable || !!preview" @click="formatActive"><WandSparkles :size="15" />格式化</button>
      <label class="sql-dialect">方言<select v-model="dialect" aria-label="SQL 方言"><option v-for="option in dialects" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <button class="sql-primary" type="button" :disabled="busy || !activeFile?.editable || !!preview" @click="saveActive"><Save :size="15" />保存文件</button>
    </header>
    <p v-if="error" class="sql-studio-notice error" role="alert">{{ error }}<button @click="error = ''" aria-label="关闭错误">×</button></p>
    <p v-if="message" class="sql-studio-notice" role="status">{{ message }}<button @click="message = ''" aria-label="关闭提示">×</button></p>
    <div class="sql-ide-shell" :style="ideStyle">
      <SqlProjectTree v-if="layout.explorer && projectId" :files="files" :references="references" :reference-ids="referenceIds" :project-name="projects.find(item => item.id === projectId)?.name" :active-id="activeId" :mapping-ids="mappingIds" :busy="busy"
        @open="openFile" @mapping="toggle(mappingIds, $event)" @reference="toggle(referenceIds, $event)" @reference-open="openReference" @choose-library="chooseLibrary" @create="createFile" @delete="deleteFile" @refresh="action(refreshFiles)" @versions="showVersions" @download="download" @rename="renameFile" />
      <aside v-else-if="layout.explorer" class="sql-project-tree sql-ide-no-project"><strong>工程资源</strong><p>新建或选择工程后查看文件。</p></aside>
      <div v-if="layout.explorer" class="sql-panel-resizer" role="separator" aria-orientation="vertical" aria-label="调整工程文件宽度" @pointerdown="startResize('explorer', $event)" @dblclick="resetSize('explorer')"></div>
      <main class="sql-ide-main">
        <SqlEditorPane v-if="projectId" ref="editorPane" :project-id="projectId" :files="files" :open-ids="openIds" :active-id="activeId" :preview="preview" :dialect="dialect" :schema-columns="schemaColumns" :workspace-context="workspaceContext"
          @open="openFile" @close="closeFile" @edit="editFile" @save="saveActive" @versions="showVersions" @download="download" @preview-close="preview = null" @selection="useSelection" @mapping-reference="useMappingReference" @diagnostics="problemCount = $event" @cursor="cursor = $event" />
        <div v-else class="sql-ide-welcome"><div class="sql-ide-welcome-mark">SQL</div><h1>开始一个 SQL 工程</h1><p>将 Mapping、参考 SQL 与生成文件放在同一工程中，由现有 Agent Runtime 协助开发。</p><button class="sql-primary" @click="newProjectOpen = true">新建工程</button></div>
        <div v-if="layout.bottom" class="sql-bottom-resizer" role="separator" aria-orientation="horizontal" aria-label="调整底部面板高度" @pointerdown="startResize('bottom', $event)" @dblclick="resetSize('bottom')"></div>
        <SqlBottomPanel v-if="layout.bottom" :style="{ height: layout.bottomHeight + 'px' }" :tab="layout.bottomTab" :problem-count="problemCount" :error="error" :message="message" :result="result" :generations="generations" @tab="layout.bottomTab = $event" @close="layout.bottom = false" @generation="selectGeneration" />
        <button v-else-if="result || problemCount" type="button" class="sql-bottom-hint" @click="layout.bottom = true">{{ problemCount ? problemCount + ' 个问题' : '查看任务与输出' }}</button>
      </main>
      <div v-if="layout.assistant" class="sql-panel-resizer" role="separator" aria-orientation="vertical" aria-label="调整 AI 助手宽度" @pointerdown="startResize('assistant', $event)" @dblclick="resetSize('assistant')"></div>
      <SqlAssistantPanel v-if="layout.assistant && projectId" :current-file="activeFile" :dialect-label="dialects.find(item => item.value === dialect)?.label" :task-running="taskRunning" :mapping-documents="mappingDocuments" :mapping-count="mappingIds.length + mappingDocuments.length" :project-files="files" :references="references" :reference-ids="referenceIds" :models="models" :agents="agents" :agent-id="agentId" :model-name="modelName" :execution-mode="executionMode" :prompt="prompt" :messages="chatTurns" :busy="busy" :generations="generations" :generation-id="generationId" :result="result" :selected-code="selectedCode"
        @remove-mapping-document="removeMappingDocument" @reference="toggle(referenceIds, $event)" @open-resource="openReference" @choose-library="chooseLibrary" @agent="agentId = $event" @model="modelName = $event" @execution-mode="chooseExecutionMode" @prompt="prompt = $event" @send="generate" @generation="selectGeneration" @resume="resume" @cancel="cancel" @preview="preview = $event" @apply="apply" @clear-selection="selectedCode = null" @reference-file="addFileMention" />
      <aside v-else-if="layout.assistant" class="sql-assistant-panel sql-ide-no-project"><strong>AI 工程助手</strong><p>选择工程后开始协作。</p></aside>
      <footer class="sql-ide-status"><CircleCheck :size="13" /><span>{{ projects.find(item => item.id === projectId)?.name || '未选择工程' }}</span><span>{{ dirtyCount ? dirtyCount + ' 个文件未保存' : '文件已保存' }}</span><span class="sql-ide-status-spacer"></span><span :class="{ 'sql-status-running': taskRunning }">{{ result?.task?.status || '就绪' }}</span><button type="button" @click="layout.bottom = true; layout.bottomTab = 'problems'">{{ problemCount }} 个问题</button><span v-if="cursor">行 {{ cursor.line }}，列 {{ cursor.column }}</span><span>UTF-8</span><span>{{ dialects.find(item => item.value === dialect)?.label || dialect }}</span></footer>
    </div>
    <Teleport to="body"><div v-if="newProjectOpen" class="sql-dialog-backdrop" @mousedown.self="newProjectOpen = false"><form class="sql-dialog" @submit.prevent="createProject"><h2>新建 SQL 工程</h2><label>工程名称<input v-model="projectName" maxlength="180" autofocus placeholder="例如：销售明细 ETL" /></label><footer><button type="button" @click="newProjectOpen = false">取消</button><button class="sql-primary" :disabled="busy || !projectName.trim()">创建工程</button></footer></form></div></Teleport>
    <Teleport to="body"><div v-if="newFileOpen" class="sql-dialog-backdrop" @mousedown.self="newFileOpen = false" @keydown.esc="newFileOpen = false"><form class="sql-dialog" role="dialog" aria-modal="true" aria-labelledby="sql-new-file-title" @submit.prevent="createFile(newFilePath.trim())"><h2 id="sql-new-file-title">新建 SQL 文件</h2><label>文件路径<input v-model="newFilePath" maxlength="240" autofocus placeholder="sql/query.sql" /></label><footer><button type="button" @click="newFileOpen = false">取消</button><button class="sql-primary" :disabled="busy || !newFilePath.trim()">创建并打开</button></footer></form></div></Teleport>
    <Teleport to="body"><div v-if="libraryOpen" class="sql-dialog-backdrop" @mousedown.self="libraryOpen = false">
      <section class="sql-dialog sql-library-dialog">
        <h2>选择 Mapping 文档（可选）</h2>
        <p>仅列出当前身份有权访问的文档，用于生成代码时提供参考上下文。</p>
        <div class="sql-library-list"><label v-for="item in libraryItems" :key="item.docId"><input type="checkbox" :checked="librarySelected.includes(item.docId)" @change="toggle(librarySelected, item.docId)" /><span>{{ item.fileName || item.title }}</span><small>v{{ item.version }}</small></label><p v-if="!libraryItems.length">文档库中暂无可用文件。</p></div>
        <footer><button type="button" @click="libraryOpen = false">取消</button><button type="button" class="sql-primary" :disabled="busy" @click="importLibrary">确认上下文</button></footer>
      </section></div></Teleport>
    <Teleport to="body"><div v-if="versionFile" class="sql-dialog-backdrop" @mousedown.self="versionFile = null"><section class="sql-dialog sql-version-dialog"><h2>{{ versionFile.path }} · 保存版本</h2><p>历史版本只供查看，不覆盖当前编辑。</p><details v-for="version in versions" :key="version.version"><summary>v{{ version.version }}{{ version.sourceTaskId ? ' · Agent 产物' : ' · 用户保存' }}</summary><pre>{{ version.content }}</pre></details><footer><button @click="versionFile = null">关闭</button></footer></section></div></Teleport>
  </section>
</template>
<script src="../js/views/SqlStudioView.js"></script>
<style src="../styles/pages/sql-studio.css"></style>
<style src="../styles/pages/sql-studio-ide.css"></style>
