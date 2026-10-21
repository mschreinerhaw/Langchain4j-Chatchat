<template>
  <section class="sql-bottom-panel" aria-label="SQL Studio 底部面板">
    <nav class="sql-bottom-tabs" aria-label="底部面板标签">
      <button
        v-for="item in tabs"
        :key="item.id"
        type="button"
        :class="{ active: tab === item.id }"
        @click="$emit('tab', item.id)"
        >{{ item.label
        }}<small v-if="item.id === 'problems' && problemCount">{{ problemCount }}</small></button
      >
      <button
        class="sql-bottom-close"
        type="button"
        aria-label="关闭底部面板"
        @click="$emit('close')"
        >×</button
      >
    </nav>
    <div class="sql-bottom-content">
      <div v-if="tab === 'problems'"
        ><p v-if="!problemCount && !error">当前没有需要处理的问题。</p
        ><AppNotice v-if="problemCount"
          >编辑器发现 {{ problemCount }} 个语法或格式问题，请查看代码中的标记。</AppNotice
        ><AppNotice v-if="error" kind="error">{{ error }}</AppNotice></div
      >
      <div v-else-if="tab === 'output'"
        ><p v-if="!message && !result?.rawAnswer">暂无输出。</p
        ><AppNotice v-if="message" kind="success">{{ message }}</AppNotice
        ><pre v-if="result?.rawAnswer">{{ result.rawAnswer }}</pre>
      </div>
      <div v-else-if="tab === 'tasks'"
        ><p v-if="!generations.length">暂无工程任务。</p
        ><button
          v-for="(generation, index) in generations"
          :key="generation.id"
          class="sql-bottom-task"
          type="button"
          @click="$emit('generation', generation.id)"
          >任务 {{ generations.length - index }} ·
          {{ generation.applied ? "已应用" : generation.taskId ? "查看执行" : "待提交" }}</button
        ></div
      >
      <div v-else
        ><p>{{
          result?.validation === "NOT_RUN" || !result
            ? "尚未执行 SQL 方言或数据库校验。"
            : result.validation
        }}</p
        ><p>SQL 文件的生成与编辑不会执行数据库语句。</p></div
      >
    </div>
  </section>
</template>
<script src="../../js/components/sql-studio/SqlBottomPanel.js"></script>
