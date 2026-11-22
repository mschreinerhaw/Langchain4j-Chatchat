<template>
  <section class="business-user-notes" aria-label="业务笔记">
    <div class="business-note-heading">
      <span v-if="busy" role="status">正在加载笔记…</span>
      <button type="button" class="business-note-primary" :disabled="busy || saving" @click="open(null)">＋ 新建笔记</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="business-note-workspace">
      <div class="business-note-list" aria-label="笔记列表">
        <p v-if="!busy && !notes.length">暂无笔记。</p>
        <button v-for="note in notes" :key="note.id" type="button"
          :aria-current="active?.id === note.id ? 'true' : undefined" :disabled="saving" @click="open(note)">
          <strong>{{ note.title }}</strong>
          <small>{{ note.owned ? (note.published ? '我的笔记 · 已发布' : '我的草稿') : '角色共享' }}</small>
        </button>
        <div v-if="page > 0 || hasMore" class="business-actions">
          <button type="button" :disabled="busy || saving || page === 0" @click="load(page - 1)">上一页</button>
          <span>{{ page + 1 }}</span>
          <button type="button" :disabled="busy || saving || !hasMore" @click="load(page + 1)">下一页</button>
        </div>
      </div>
      <form v-if="active" class="business-card business-user-note-editor" @submit.prevent="save(false)">
        <template v-if="active.owned">
          <label class="business-note-field">标题
            <input ref="titleInput" v-model="active.title" maxlength="120" required :disabled="saving" placeholder="输入笔记标题" />
          </label>
          <div class="business-note-toolbar" role="group" aria-label="文字格式">
            <template v-if="!preview">
              <button v-for="format in formats" :key="format.name" type="button" :title="format.name"
                :aria-label="format.name" :disabled="saving" @click="applyFormat(format)">{{ format.label }}</button>
            </template>
            <button type="button" :aria-pressed="preview" @click="preview = !preview">{{ preview ? "继续编辑" : "预览" }}</button>
          </div>
          <textarea v-if="!preview" ref="bodyInput" v-model="active.body" maxlength="20000" rows="13"
            :disabled="saving" aria-label="笔记正文" placeholder="输入笔记正文，支持 Markdown" />
        </template>
        <h2 v-else>{{ active.title }}</h2>
        <div v-if="preview || !active.owned" class="message-markdown business-note-preview" v-html="renderedBody"></div>
        <template v-if="active.owned">
          <div class="business-note-publication">
            <label class="business-note-field">发布给
              <select v-model="selectedRole" :disabled="saving || !roles.length">
                <option value="" disabled>{{ roles.length ? "请选择角色" : "暂无可发布的角色" }}</option>
                <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option>
              </select>
            </label>
            <div class="business-actions">
              <button type="submit" :disabled="saving || busy || !active.title.trim()">保存草稿</button>
              <button class="business-note-primary" type="button"
                :disabled="saving || busy || !selectedRole || !active.title.trim() || !active.body.trim()"
                @click="save(true)">{{ active.published ? "更新发布" : "发布笔记" }}</button>
              <button v-if="active.id" type="button" :disabled="saving || busy" @click="remove">删除笔记</button>
            </div>
          </div>
          <p v-if="active.published" class="business-note-help">保存草稿后，角色成员仍会看到上次发布的版本。</p>
        </template>
        <p v-if="notice" role="status">{{ notice }}</p>
      </form>
      <div v-else class="business-note-placeholder">选择一条笔记，或新建笔记开始写作。</div>
    </div>
    <AppConfirmDialog v-if="confirmation" v-bind="confirmation"
      @confirm="settleConfirmation(true)" @cancel="settleConfirmation(false)" />
  </section>
</template>
<script src="../js/components/BusinessUserNotes.js"></script>
