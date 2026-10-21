<template>
  <section
    class="user-space-browser"
    aria-label="用户空间文件浏览器"
    @dragover="dragOver"
    @drop="dropFiles"
  >
    <header class="space-heading"
      ><div
        ><span class="space-eyebrow">数据车间 / 用户空间</span><h1>用户空间</h1
        ><p>按用途整理文件、脚本与分析资料，在开发和对话中引用。</p></div
      ><div class="space-actions"
        ><button
          class="ghost-button"
          :disabled="busy"
          @click="
            folderName = '';
            folderDialog = true;
          "
          ><FolderPlus :size="16" />新建文件夹</button
        ><button
          class="ghost-button"
          :disabled="busy || !canCreateScript"
          @click="$emit('new-script')"
          ><FileCode2 :size="16" />新建脚本</button
        ><button class="primary-button" :disabled="busy" @click="uploadInput.click()"
          ><Upload :size="16" />上传文件</button
        ><input
          ref="uploadInput"
          type="file"
          multiple
          hidden
          :accept="accept"
          @change="uploadFiles" /></div
    ></header>
    <AppNotice v-if="error" kind="error" dismissible @close="error = ''">{{ error }}</AppNotice>
    <AppNotice v-if="status" kind="success" dismissible @close="status = ''">{{
      status
    }}</AppNotice>
    <div class="space-layout">
      <aside class="space-folders" aria-label="用户空间文件夹"
        ><button :class="{ active: currentFolder === 'all' }" @click="openFolder('all')"
          ><Files :size="17" /><span>全部文件</span><small>{{ totalFiles }}</small></button
        >
        <button
          v-for="folder in folders"
          :key="folder.id"
          :class="{ active: currentFolder === folder.id }"
          :style="{
            'padding-left': `${14 + Math.max(0, folderTrail(folder.id, folders).length - 1) * 14}px`,
          }"
          @click="openFolder(folder.id)"
          ><Folder :size="17" /><span>{{ folder.name }}</span
          ><small>{{ countFolder(folder.id) }}</small></button
        >
        <div v-if="space" class="space-quota"
          ><span>用户文件空间</span
          ><strong>{{ formatBytes(space.usedBytes) }} / {{ formatBytes(space.quotaBytes) }}</strong
          ><progress
            :value="space.usedBytes"
            :max="space.quotaBytes"
            aria-label="用户文件空间用量"
          ></progress
        ></div>
      </aside>
      <main class="space-main">
        <div class="space-toolbar"
          ><nav class="space-breadcrumb" aria-label="文件夹路径"
            ><button @click="openFolder('all')"><HardDrive :size="15" />用户空间</button
            ><template v-for="folder in breadcrumb" :key="folder.id"
              ><ChevronRight :size="13" /><button @click="openFolder(folder.id)">{{
                folder.name
              }}</button></template
            ><span v-if="currentFolder === 'all'">/ 全部文件</span></nav
          ><div class="space-view-tools"
            ><button
              class="icon-button"
              aria-label="刷新用户空间"
              title="刷新"
              :disabled="busy"
              @click="refreshAll"
              ><RefreshCw :size="16" :class="{ spinning: loading }" /></button
            ><button
              class="icon-button"
              aria-label="列表视图"
              title="列表视图"
              :aria-pressed="viewMode === 'list'"
              @click="viewMode = 'list'"
              ><List :size="16" /></button
            ><button
              class="icon-button"
              aria-label="网格视图"
              title="网格视图"
              :aria-pressed="viewMode === 'grid'"
              @click="viewMode = 'grid'"
              ><LayoutGrid :size="16" /></button></div
        ></div>
        <div class="space-filter"
          ><label class="space-search"
            ><Search :size="17" /><input
              v-model="query"
              type="search"
              aria-label="搜索用户空间文件"
              placeholder="搜索文件名称、用途或状态"
              @input="hits = null"
              @keydown.enter.prevent="knowledgeEnabled && searchContent()" /></label
          ><button
            v-if="knowledgeEnabled"
            class="ghost-button"
            :disabled="busy || !query.trim()"
            @click="searchContent"
            ><Search :size="15" />检索内容</button
          ><select v-model="sortBy" aria-label="文件排序"
            ><option value="updated">最近更新</option
            ><option value="name">文件名称</option
            ><option value="size">文件大小</option></select
          ></div
        >
        <div v-if="selected.length" class="space-selection"
          ><span>已选择 {{ selected.length }} 个文件</span
          ><button
            class="ghost-button compact-button"
            :disabled="busy"
            @click="
              moveTarget = currentFolder === 'all' ? 'root' : currentFolder;
              moveDialog = true;
            "
            ><FolderInput :size="15" />移动到</button
          ><button
            class="primary-button compact-button"
            :disabled="busy || !selectedEntries.every(canAnalyze)"
            @click="openAnalysis"
            ><Sparkles :size="15" />分析选中文件</button
          ><button class="ghost-button compact-button" :disabled="busy" @click="selected = []"
            >取消选择</button
          ></div
        >
        <div v-if="childFolders.length && !query && !hits" class="space-folder-grid"
          ><button
            v-for="folder in childFolders"
            :key="folder.id"
            @dblclick="openFolder(folder.id)"
            @click="openFolder(folder.id)"
            ><Folder :size="30" /><div
              ><strong>{{ folder.name }}</strong
              ><small>{{ countFolder(folder.id) }} 个文件</small></div
            ><ChevronRight :size="16" /></button
        ></div>
        <div class="space-list-heading"
          ><strong>{{ hits ? "知识检索结果" : folderNameLabel }}</strong
          ><span>{{ resultTotal }} 个文件{{ loading ? " · 正在加载…" : "" }}</span
          ><button
            v-if="isCustomFolder"
            class="space-text-action"
            :disabled="busy"
            @click="removeCurrentFolder"
            >删除空文件夹</button
          ></div
        >
        <div v-if="!visible.length" class="space-empty"
          ><FolderOpen :size="46" /><h2>{{ query ? "没有找到匹配文件" : "此文件夹还没有文件" }}</h2
          ><p>{{
            query
              ? "调整关键词，或使用内容检索查找资料。"
              : "上传文件，或将已有文件移动到这里；脚本和分析记录会按用途显示。"
          }}</p
          ><button
            v-if="!query"
            class="primary-button"
            :disabled="busy"
            @click="uploadInput.click()"
            ><Upload :size="16" />上传文件</button
          ></div
        >
        <div v-else-if="viewMode === 'list'" class="space-table-wrap"
          ><table
            ><thead
              ><tr
                ><th class="space-check"
                  ><input
                    type="checkbox"
                    aria-label="选择当前文件"
                    :checked="allSelected"
                    @change="toggleAll" /></th
                ><th>名称</th><th>用途</th><th>大小</th><th>状态</th><th>更新时间</th
                ><th class="space-actions-column">操作</th></tr
              ></thead
            ><tbody
              ><tr
                v-for="file in visible"
                :key="file.key"
                :class="{ selected: selected.includes(file.key) }"
                @dblclick="openFile(file)"
                ><td
                  ><input
                    type="checkbox"
                    :aria-label="'选择 ' + file.name"
                    :checked="selected.includes(file.key)"
                    @change="toggle(file.key)" /></td
                ><td
                  ><button class="space-file-name" @click="openFile(file)"
                    ><component :is="fileIcon(file)" :size="20" /><span
                      ><strong>{{ file.name }}</strong
                      ><small v-if="file.version">v{{ file.version }}</small
                      ><small v-if="hitFor(file)">{{ hitFor(file).excerpt }}</small></span
                    ></button
                  ></td
                ><td>{{ folderLabel(file.folderId) }}</td
                ><td>{{ formatBytes(file.size) }}</td
                ><td
                  ><span
                    class="space-state"
                    :class="{ available: ['AVAILABLE', 'PUBLISHED'].includes(file.state) }"
                    >{{ stateLabel(file.state) }}</span
                  ></td
                ><td>{{ formatTime(file.updatedAt) }}</td
                ><td
                  ><div class="space-row-actions"
                    ><button
                      class="icon-button"
                      :aria-label="'打开 ' + file.name"
                      title="打开"
                      @click="openFile(file)"
                      ><Eye :size="15" /></button
                    ><button
                      class="icon-button"
                      :aria-label="'下载 ' + file.name"
                      title="下载"
                      :disabled="busy || !readable(file)"
                      @click="download(file)"
                      ><Download :size="15" /></button
                    ><button
                      v-if="canDelete(file)"
                      class="icon-button space-danger"
                      :aria-label="'删除 ' + file.name"
                      title="删除"
                      :disabled="busy"
                      @click="remove(file)"
                      ><Trash2 :size="15" /></button></div></td></tr></tbody></table
        ></div>
        <div v-else class="space-file-grid"
          ><article
            v-for="file in visible"
            :key="file.key"
            :class="{ selected: selected.includes(file.key) }"
            @dblclick="openFile(file)"
            ><div
              ><input
                type="checkbox"
                :aria-label="'选择 ' + file.name"
                :checked="selected.includes(file.key)"
                @change="toggle(file.key)"
              /><span class="space-state">{{ stateLabel(file.state) }}</span></div
            ><button class="space-grid-open" @click="openFile(file)"
              ><component :is="fileIcon(file)" :size="38" /><strong>{{ file.name }}</strong></button
            ><small>{{ formatBytes(file.size) }} · {{ folderLabel(file.folderId) }}</small
            ><footer
              ><button class="space-text-action" @click="openFile(file)">打开</button
              ><button
                class="space-text-action"
                :disabled="busy || !readable(file)"
                @click="download(file)"
                >下载</button
              ><button
                v-if="canDelete(file)"
                class="space-text-action space-danger"
                :disabled="busy"
                @click="remove(file)"
                >删除</button
              ></footer
            ></article
          ></div
        >
        <footer class="space-footer"
          ><span>共 {{ resultTotal }} 个文件</span
          ><nav class="space-pagination" aria-label="文件分页"
            ><button
              class="ghost-button compact-button"
              :disabled="busy || page <= 1"
              @click="goPage(1)"
              >首页</button
            ><button
              class="ghost-button compact-button"
              :disabled="busy || page <= 1"
              @click="goPage(page - 1)"
              >上一页</button
            ><label
              >第
              <select
                :value="page"
                aria-label="选择页码"
                :disabled="busy"
                @change="goPage(Number($event.target.value))"
                ><option v-for="number in pageCount" :key="number" :value="number">{{
                  number
                }}</option></select
              >
              / {{ pageCount }} 页</label
            ><button
              class="ghost-button compact-button"
              :disabled="busy || page >= pageCount"
              @click="goPage(page + 1)"
              >下一页</button
            ><button
              class="ghost-button compact-button"
              :disabled="busy || page >= pageCount"
              @click="goPage(pageCount)"
              >末页</button
            ><select v-model.number="pageSize" aria-label="每页文件数" :disabled="busy"
              ><option :value="10">10 条/页</option
              ><option :value="25">25 条/页</option
              ><option :value="50">50 条/页</option
              ><option :value="100">100 条/页</option></select
            ></nav
          ></footer
        >
      </main>
    </div>
    <section v-if="analysisOpen" class="space-analysis" aria-label="选中文件分析"
      ><header
        ><h2><Sparkles :size="19" />分析选中文件</h2
        ><button
          class="icon-button"
          aria-label="关闭文件分析"
          :disabled="busy"
          @click="analysisOpen = false"
          ><X :size="17" /></button></header
      ><p>已选 {{ selected.length }} 个文件。历史分析记录只作为待核对的参考。</p
      ><label
        >分析模型<select v-model="modelName" :disabled="busy"
          ><option v-for="model in models" :key="model.value" :value="model.value">{{
            model.label || model.value
          }}</option></select
        ></label
      ><label
        >分析目标<textarea
          v-model="instruction"
          :disabled="busy"
          maxlength="16000"
          placeholder="说明计算指标、业务口径和需要回答的问题"
        ></textarea></label
      ><button class="primary-button" :disabled="busy || !instruction.trim()" @click="analyze"
        ><Sparkles :size="16" />{{ analyzing ? "正在分析…" : "开始分析" }}</button
      ><p v-if="analysisStatus" role="status">{{ analysisStatus }}</p
      ><ReportMarkdown v-if="report" :content="report"
    /></section>
    <Teleport to="body"
      ><div
        v-if="folderDialog || moveDialog || preview"
        class="space-dialog-backdrop"
        @mousedown.self="!busy && closeDialog()"
        ><section
          class="space-dialog"
          :class="{ 'space-preview': preview }"
          role="dialog"
          aria-modal="true"
          :aria-label="preview ? '文件预览' : moveDialog ? '移动文件' : '新建文件夹'"
          ><header
            ><h2>{{ preview ? preview.file.name : moveDialog ? "移动文件" : "新建文件夹" }}</h2
            ><button class="icon-button" aria-label="关闭窗口" :disabled="busy" @click="closeDialog"
              ><X :size="17" /></button></header
          ><template v-if="preview"
            ><dl
              ><dt>用途</dt><dd>{{ folderLabel(preview.file.folderId) }}</dd
              ><dt>大小</dt><dd>{{ formatBytes(preview.file.size) }}</dd
              ><dt v-if="preview.file.taskId">所属任务</dt
              ><dd v-if="preview.file.taskId">{{ preview.file.taskId }}</dd></dl
            ><p v-if="preview.file.taskId">此文件属于任务归档，原件保留用于追溯。</p
            ><pre v-if="preview.text !== null">{{ preview.text }}</pre
            ><img v-else-if="preview.url" :src="preview.url" :alt="preview.file.name" /><p v-else
              >此格式可下载后查看。</p
            ><footer
              ><button
                v-if="canCreateScript && isPythonData(preview.file)"
                class="ghost-button"
                :disabled="busy || !readable(preview.file)"
                @click="usePythonData(preview.file)"
                ><FileCode2 :size="16" />用于 Python 开发</button
              ><button
                v-if="canCreateScript && preview.text !== null && /\.py$/i.test(preview.file.name)"
                class="ghost-button"
                :disabled="busy"
                @click="openPythonDraft"
                ><FileCode2 :size="16" />在 Python 中打开副本</button
              ><button
                class="primary-button"
                :disabled="busy || !readable(preview.file)"
                @click="download(preview.file)"
                ><Download :size="16" />下载文件</button
              ></footer
            ></template
          ><form v-else @submit.prevent="moveDialog ? moveFiles() : createFolder()"
            ><AppNotice v-if="error" kind="error">{{ error }}</AppNotice
            ><label v-if="folderDialog"
              >文件夹名称<input
                v-model="folderName"
                maxlength="100"
                autofocus
                placeholder="例如：销售分析" /></label
            ><label v-if="moveDialog"
              >目标文件夹<select v-model="moveTarget"
                ><option value="root">用户空间根目录</option
                ><option v-for="folder in folders" :key="folder.id" :value="folder.id">{{
                  folderTrail(folder.id, folders)
                    .map((item) => item.name)
                    .join(" / ")
                }}</option></select
              ></label
            ><footer
              ><button type="button" class="ghost-button" :disabled="busy" @click="closeDialog"
                >取消</button
              ><button
                class="primary-button"
                :disabled="busy || (folderDialog && !folderName.trim())"
                >{{ moveDialog ? "移动到此处" : "创建文件夹" }}</button
              ></footer
            ></form
          ></section
        ></div
      ></Teleport
    >
    <AppConfirmDialog
      v-if="confirmation"
      :title="confirmation.title"
      :message="confirmation.message"
      @confirm="resolveConfirm(true)"
      @cancel="resolveConfirm(false)"
    />
  </section>
</template>
<script src="../js/components/UserSpaceBrowser.js"></script>
<style src="../styles/pages/user-space-browser.css"></style>
