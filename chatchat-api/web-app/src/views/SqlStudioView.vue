<template>
  <section class="sql-studio">
    <header class="sql-studio-bar"><div class="sql-studio-brand"><span>SQL</span><div><strong>SQL Studio</strong><small>Mapping 驱动的 SQL 工程</small></div></div>
      <select aria-label="SQL 工程" :value="projectId" :disabled="busy" @change="selectProject($event.target.value)"><option value="" disabled>选择工程</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
      <button type="button" :disabled="busy" @click="newProjectOpen = true">＋ 新建工程</button>
      <label class="sql-dialect">方言<select v-model="dialect" aria-label="SQL 方言"><option v-for="option in dialects" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <button class="sql-primary" type="button" :disabled="busy || !activeFile?.editable || !!preview" @click="saveActive">保存文件</button>
    </header>
    <p v-if="error" class="sql-studio-notice error" role="alert">{{ error }}<button @click="error = ''" aria-label="关闭错误">×</button></p>
    <p v-if="message" class="sql-studio-notice" role="status">{{ message }}<button @click="message = ''" aria-label="关闭提示">×</button></p>
    <div v-if="projectId" class="sql-studio-grid">
      <SqlProjectTree :files="files" :active-id="activeId" :mapping-ids="mappingIds" :busy="busy" @open="openFile" @mapping="toggle(mappingIds, $event)" @upload="upload" @create="createFile" @refresh="action(refreshFiles)" />
      <SqlEditorPane :project-id="projectId" :files="files" :open-ids="openIds" :active-id="activeId" :preview="preview" :dialect="dialect" :schema-columns="schemaColumns" :workspace-context="workspaceContext" @open="openFile" @close="closeFile" @edit="editFile" @save="saveActive" @versions="showVersions" @download="download" @preview-close="preview = null" />
      <SqlAssistantPanel :mapping-count="mappingIds.length" :references="references" :reference-ids="referenceIds" :models="models" :agents="agents" :agent-id="agentId" :model-name="modelName" :prompt="prompt" :busy="busy" :generations="generations" :generation-id="generationId" :result="result"
        @reference="toggle(referenceIds, $event)" @agent="agentId = $event" @model="modelName = $event" @prompt="prompt = $event" @generate="generate" @generation="selectGeneration" @resume="resume" @cancel="cancel" @preview="preview = $event" @apply="apply" />
    </div>
    <div v-else class="sql-studio-empty"><strong>创建你的 SQL 工程</strong><p>上传 Mapping，选择历史 SQL 风格参考，使用现有 Agent 生成并审核工程文件。</p><button class="sql-primary" @click="newProjectOpen = true">新建工程</button></div>
    <Teleport to="body"><div v-if="newProjectOpen" class="sql-dialog-backdrop" @mousedown.self="newProjectOpen = false"><form class="sql-dialog" @submit.prevent="createProject"><h2>新建 SQL 工程</h2><label>工程名称<input v-model="projectName" maxlength="180" autofocus placeholder="例如：销售明细 ETL" /></label><footer><button type="button" @click="newProjectOpen = false">取消</button><button class="sql-primary" :disabled="busy || !projectName.trim()">创建工程</button></footer></form></div></Teleport>
    <Teleport to="body"><div v-if="versionFile" class="sql-dialog-backdrop" @mousedown.self="versionFile = null"><section class="sql-dialog sql-version-dialog"><h2>{{ versionFile.path }} · 保存版本</h2><p>历史版本仅供查看，不覆盖当前编辑。</p><details v-for="version in versions" :key="version.version"><summary>v{{ version.version }}{{ version.sourceTaskId ? ' · Agent 产物' : ' · 用户保存' }}</summary><pre>{{ version.content }}</pre></details><footer><button @click="versionFile = null">关闭</button></footer></section></div></Teleport>
  </section>
</template>
<script src="../js/views/SqlStudioView.js"></script>
<style src="../styles/pages/sql-studio.css"></style>
