<template>
  <section class="python-code-diff" aria-label="AI 代码变更预览">
    <header>
      <div>
        <strong>{{ action === "optimize" ? "优化变更" : "修复变更" }}</strong>
        <span>{{ scope }}</span>
      </div>
      <p><b>+{{ diff.additions }}</b><i>-{{ diff.deletions }}</i></p>
    </header>
    <div class="change-note">
      <b>变更说明</b>
      <span>{{ request || (action === "optimize" ? "优化代码质量" : "修复代码问题") }}</span>
    </div>
    <div class="diff-lines" role="region" aria-label="Git 风格逐行差异">
      <div v-for="(line, index) in diff.lines" :key="`${index}-${line.type}`" :class="`diff-${line.type}`">
        <template v-if="line.type !== 'omitted'">
          <span class="old-line">{{ line.oldLine || "" }}</span>
          <span class="new-line">{{ line.newLine || "" }}</span>
          <b class="marker">{{ line.type === "added" ? "+" : line.type === "deleted" ? "-" : " " }}</b>
          <code>{{ line.content || " " }}</code>
        </template>
        <span v-else class="omitted">{{ line.content }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  diff: { type: Object, required: true },
  action: { type: String, default: "optimize" },
  request: { type: String, default: "" },
  scope: { type: String, default: "当前脚本" }
});
</script>

<style scoped>
.python-code-diff{border-top:1px solid #dce5f0;background:#fff}.python-code-diff>header{display:flex;align-items:center;justify-content:space-between;padding:9px 10px;background:#f7f9fc}.python-code-diff>header>div{display:grid;gap:2px}.python-code-diff>header strong{font-size:11px;color:#26364d}.python-code-diff>header span{font-size:9px;color:#7c8da3}.python-code-diff>header p{display:flex;gap:7px;margin:0;font:700 10px/1 Consolas,monospace}.python-code-diff>header p b{color:#16804c}.python-code-diff>header p i{color:#b42318;font-style:normal}.change-note{display:grid;gap:3px;padding:8px 10px;border-top:1px solid #e8edf3;border-bottom:1px solid #e8edf3;background:#fbfcfe}.change-note b{font-size:9px;color:#52657e}.change-note span{font-size:10px;line-height:1.45;color:#314158}.diff-lines{max-height:300px;overflow:auto;background:#fbfcfe;font:10px/1.55 "JetBrains Mono",Consolas,monospace}.diff-lines>div{min-width:max-content;display:grid;grid-template-columns:30px 30px 17px minmax(220px,1fr)}.diff-lines span,.diff-lines b,.diff-lines code{padding-top:1px;padding-bottom:1px}.old-line,.new-line{padding-right:6px;border-right:1px solid #e5eaf0;color:#9aa7b8;text-align:right;user-select:none}.marker{text-align:center;color:#8291a4;user-select:none}.diff-lines code{padding-right:10px;color:#344054;white-space:pre}.diff-added{background:#eaf8ef}.diff-added .marker,.diff-added code{color:#167044}.diff-deleted{background:#fff0f0}.diff-deleted .marker,.diff-deleted code{color:#b42318}.diff-omitted{display:block!important;min-width:0!important;padding:4px 10px;background:#f1f4f8;color:#8492a6;text-align:center}.omitted{padding:0!important;font-style:italic}
</style>
