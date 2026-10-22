<template>
  <section class="feature-view unified-management">
    <header
      ><div
        ><h2>MCP 工具发布</h2
        ><p>管理受控工具的定义、测试和版本。发布不会授予用户数据访问权限。</p></div
      ><button :disabled="busy" @click="refresh">刷新</button></header
    >
    <p v-if="notice" role="status" :class="{ error: failed }">{{ notice }}</p>
    <p v-if="busy" role="status">正在处理…</p>
    <div class="management-columns">
      <section class="management-card"
        ><h3>发布版本</h3
        ><p v-if="!rows.length && !busy">暂无工具。创建草稿后完成校验和测试，再发布。</p>
        <article v-for="row in rows" :key="row.id" class="release-row">
          <strong>{{ row.title }}</strong
          ><span>v{{ row.releaseNumber }} · {{ states[row.state] || row.state }}</span>
          <small>{{ new Date(row.createdAt).toLocaleString() }}</small>
          <div class="management-actions">
            <button @click="select(row)">查看 / 新版本</button>
            <button
              v-if="editable && !row.publishedAt"
              :disabled="busy"
              @click="action(row, 'validate')"
              >校验</button
            >
            <button
              v-if="editable && ['VALIDATED', 'TESTED'].includes(row.state)"
              :disabled="busy"
              @click="test(row)"
              >合约测试</button
            >
            <button
              v-if="publishable && row.state === 'TESTED'"
              :disabled="busy"
              @click="action(row, 'publish')"
              >发布</button
            >
            <button
              v-if="publishable && row.state === 'RUNNING'"
              :disabled="busy"
              @click="confirmAction(row, 'offline')"
              >下线</button
            >
            <button
              v-if="publishable && row.state === 'OFFLINE'"
              :disabled="busy"
              @click="confirmAction(row, 'rollback')"
              >回滚到此版本</button
            >
          </div>
          <details v-if="row.testEvidenceJson"
            ><summary>测试记录</summary><pre>{{ pretty(row.testEvidenceJson) }}</pre>
          </details>
        </article>
      </section>
      <form class="management-card" @submit.prevent="save"
        ><h3>{{ form.logicalId ? "创建下一版本" : "创建工具草稿" }}</h3>
        <p v-if="form.logicalId">逻辑标识与授权对象保持稳定；保存会生成新的草稿版本。</p>
        <label
          >工具名称<input v-model.trim="form.title" required maxlength="256" :disabled="!editable"
        /></label>
        <label
          >逻辑名称<input
            v-model.trim="definition.domainCode"
            required
            placeholder="例如 sales_summary"
            :disabled="!!form.logicalId || !editable"
        /></label>
        <label
          >执行能力<select
            v-model="definition.parentToolName"
            required
            :disabled="!!form.logicalId || !editable"
            @change="loadTemplates"
            ><option value="">选择受控模板能力</option
            ><option v-for="parent in parents" :key="parent.toolName" :value="parent.toolName">{{
              parent.title
            }}</option></select
          ></label
        >
        <label
          >使用角色<select
            v-model="definition.roleId"
            required
            :disabled="!!form.logicalId || !editable"
            @change="loadTemplates"
            ><option value="">选择已授权角色</option
            ><option v-for="role in roles" :key="role.id" :value="role.id">{{
              role.roleName
            }}</option></select
          ></label
        >
        <p>这里只能绑定该角色已获授权的模板；授权范围在“权限管理”中维护。</p>
        <fieldset :disabled="!editable"
          ><legend>受控模板</legend
          ><p v-if="!templates.length">选择执行能力和角色后加载可用模板；没有授权时列表为空。</p
          ><label v-for="item in templates" :key="item.key" class="check"
            ><input v-model="definition.templateKeys" type="checkbox" :value="item.key" />{{
              item.title
            }}</label
          ></fieldset
        >
        <label>输入 Schema<textarea v-model="inputSchema" rows="6" :disabled="!editable" /></label>
        <label>输出 Schema<textarea v-model="outputSchema" rows="6" :disabled="!editable" /></label>
        <details
          ><summary>合约测试样例</summary
          ><p>检查输入、输出样例和当前角色的模板授权。真实数据执行另在查询服务中测试。</p
          ><label>输入样例<textarea v-model="inputSample" rows="4" /></label
          ><label>输出样例<textarea v-model="outputSample" rows="4" /></label
        ></details>
        <div class="management-actions"
          ><button v-if="editable" type="submit" :disabled="busy || !definition.templateKeys.length"
            >保存新草稿</button
          ><button type="button" @click="reset">新建工具</button></div
        >
      </form>
    </div>
  </section>
</template>
<script src="../js/views/ToolPublicationsView.js"></script>
