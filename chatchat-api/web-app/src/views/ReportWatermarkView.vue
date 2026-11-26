<template>
  <section class="permission-console permission-audit-page">
    <header class="permission-page-heading">
      <div>
        <nav aria-label="面包屑">权限管理 <span>/</span> 报告水印</nav>
        <h1>报告水印</h1>
        <p>管理 PDF 水印、允许导出的角色及导出记录。</p>
      </div>
    </header>
    <nav class="permission-category-tabs" aria-label="报告导出管理">
      <button type="button" :class="{ active: tab === 'settings' }" @click="select('settings')">导出设置</button>
      <button type="button" :class="{ active: tab === 'audit' }" @click="select('audit')">导出审计</button>
    </nav>
    <p v-if="error" class="permission-notice error" role="alert">{{ error }}</p>
    <p v-if="success" class="permission-notice" role="status">{{ success }}</p>
    <form v-if="tab === 'settings' && policy" @submit.prevent="save">
      <div class="permission-toolbar">
        <label><span><input v-model="policy.watermarkEnabled" type="checkbox" /> 启用 PDF 水印</span></label>
        <label class="permission-search">水印文字
          <input v-model.trim="policy.watermark" :disabled="!policy.watermarkEnabled"
            :required="policy.watermarkEnabled" maxlength="120" placeholder="输入每页显示的水印" />
        </label>
        <button type="submit" class="permission-primary" :disabled="busy">保存设置</button>
      </div>
      <h2>允许导出的角色</h2>
      <p v-if="policy.version === 0">初次保存前仅权限管理员可导出。保存后按以下角色授权。</p>
      <div class="permission-table-wrap">
        <table class="permission-table">
          <thead><tr><th>角色</th><th>允许导出 PDF</th></tr></thead>
          <tbody>
            <tr v-for="role in roles" :key="role.id">
              <td>{{ role.name }} <span v-if="!role.enabled">（已停用）</span></td>
              <td>
                <input v-model="policy.roleIds" type="checkbox" :value="role.id" :disabled="!role.enabled"
                  :aria-label="`允许 ${role.name} 导出 PDF`" />
              </td>
            </tr>
            <tr v-if="!roles.length"><td colspan="2" class="permission-empty">当前租户暂无角色</td></tr>
          </tbody>
        </table>
      </div>
    </form>
    <template v-else-if="tab === 'audit'">
      <div class="permission-toolbar"><button type="button" :disabled="busy" @click="loadAudit(page)">刷新</button></div>
      <div class="permission-table-wrap">
        <table class="permission-table">
          <thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>报告标识</th><th>结果</th><th>详情</th></tr></thead>
          <tbody>
            <tr v-for="row in audit.items" :key="row.id">
              <td>{{ new Date(row.createdAt).toLocaleString("zh-CN") }}</td>
              <td>{{ row.actorName || row.actorId }}</td>
              <td>{{ row.actionName === "policy-change" ? "修改设置" : "导出 PDF" }}</td>
              <td>{{ row.resourceId }}</td>
              <td>{{ { success: "成功", denied: "已拒绝", failure: "失败" }[row.result] || row.result }}</td>
              <td>{{ { ROLE_NOT_ALLOWED: "角色未授权", POLICY_CHANGED: "导出规则已更新", RENDER_FAILED: "PDF 生成失败" }[row.detail] || row.detail }}</td>
            </tr>
            <tr v-if="!audit.items.length"><td colspan="6" class="permission-empty">暂无导出记录</td></tr>
          </tbody>
        </table>
      </div>
      <footer class="permission-pagination">
        <span>共 {{ audit.total }} 条</span>
        <button type="button" :disabled="busy || page <= 1" @click="loadAudit(page - 1)">上一页</button>
        <span>{{ page }} / {{ Math.max(1, audit.pages) }}</span>
        <button type="button" :disabled="busy || page >= audit.pages" @click="loadAudit(page + 1)">下一页</button>
      </footer>
    </template>
    <p v-else-if="busy" role="status">正在加载设置…</p>
  </section>
</template>

<script src="../js/views/ReportWatermarkView.js"></script>
