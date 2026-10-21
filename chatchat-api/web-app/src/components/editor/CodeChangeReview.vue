<template>
  <Teleport to="body">
    <div class="code-review-backdrop" @keydown.esc.stop.prevent="$emit('close')">
      <section
        ref="dialog"
        class="code-review-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="审查代码变更"
        tabindex="-1"
      >
        <header
          ><div
            ><strong>审查代码变更</strong
            ><small>{{ title }} · {{ files.length }} 个文件</small></div
          ><button type="button" aria-label="关闭变更对比" @click="$emit('close')"
            ><X :size="18" /></button
        ></header>
        <nav aria-label="变更文件"
          ><button
            v-for="(file, index) in files"
            :key="file.path"
            type="button"
            :class="{ active: index === selected }"
            :aria-pressed="index === selected"
            @click="selected = index"
            >{{ file.isNew ? "新增" : "修改" }} · {{ file.path }}</button
          ></nav
        >
        <div class="code-review-toolbar"
          ><span class="review-added">+{{ summary.additions }} 行新增</span
          ><span class="review-deleted">−{{ summary.deletions }} 行删除</span
          ><span v-if="!summary.changed">没有代码差异</span
          ><div
            ><button type="button" :aria-pressed="!sideBySide" @click="sideBySide = !sideBySide">{{
              sideBySide ? "统一视图" : "并排视图"
            }}</button
            ><button type="button" aria-label="上一处变更" @click="navigate(-1)"
              ><ChevronUp :size="14" />上一处</button
            ><button type="button" aria-label="下一处变更" @click="navigate(1)"
              ><ChevronDown :size="14" />下一处</button
            ></div
          ></div
        >
        <div class="code-review-labels"
          ><span>修改前{{ current?.isNew ? " · 文件尚不存在" : "" }}</span
          ><span>修改后 · AI 建议</span></div
        >
        <div ref="host" class="code-review-editor" aria-label="代码差异编辑器"></div>
        <footer
          ><p role="status">{{ blockedReason || hint }}</p
          ><button type="button" @click="$emit('close')">返回</button
          ><button
            type="button"
            class="code-review-accept"
            :disabled="busy || !!blockedReason || !hasChanges"
            @click="$emit('apply')"
            >{{ busy ? "正在应用…" : applyLabel }}</button
          ></footer
        >
      </section>
    </div>
  </Teleport>
</template>
<script src="../../js/components/editor/CodeChangeReview.js"></script>
<style src="../../styles/components/editor/code-change-review.css"></style>
