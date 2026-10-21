<template>
  <section class="python-code-diff" aria-label="AI 代码变更预览">
    <header>
      <div>
        <strong>{{
          {
            generate: "生成变更",
            fix: "修复变更",
            optimize: "优化变更",
            continue: "续写变更",
            test: "新增测试文件",
          }[action] || "代码变更"
        }}</strong>
        <span>{{ scope }}</span>
      </div>
      <p
        ><b>+{{ diff.additions }}</b
        ><i>-{{ diff.deletions }}</i></p
      >
    </header>
    <div class="change-note">
      <b>变更说明</b>
      <span>{{ request || (action === "optimize" ? "优化代码质量" : "修复代码问题") }}</span>
    </div>
    <div class="diff-lines" role="region" aria-label="Git 风格逐行差异">
      <div
        v-for="(line, index) in diff.lines"
        :key="`${index}-${line.type}`"
        :class="`diff-${line.type}`"
      >
        <template v-if="line.type !== 'omitted'">
          <span class="old-line">{{ line.oldLine || "" }}</span>
          <span class="new-line">{{ line.newLine || "" }}</span>
          <b class="marker">{{
            line.type === "added" ? "+" : line.type === "deleted" ? "-" : " "
          }}</b>
          <code>{{ line.content || " " }}</code>
        </template>
        <span v-else class="omitted">{{ line.content }}</span>
      </div>
    </div>
  </section>
</template>

<script src="../js/components/PythonCodeDiff.js"></script>

<style scoped src="../styles/components/python-code-diff.css"></style>
