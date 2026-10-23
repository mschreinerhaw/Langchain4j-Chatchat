<template>
  <section class="permission-console template-capability-workspace" :aria-busy="busy">
    <header class="permission-page-heading">
      <div><nav>能力发布 / 模板工具</nav><h1>模板工具发布</h1><p>选择获准使用的模板，发布业务工具，再分配给指定 Agent。</p></div>
      <button :disabled="busy" @click="load">刷新</button>
    </header>
    <p v-if="notice" class="permission-notice" :class="{ error: failed }" :role="failed ? 'alert' : 'status'">{{ notice }}</p>
    <nav class="permission-category-tabs" aria-label="模板工具页面">
      <button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" @click="tab = item.id">{{ item.label }}</button>
      <button v-if="advanced" :class="{ active: tab === 'advanced' }" @click="tab = 'advanced'">历史版本管理</button>
    </nav>
    <ToolPublicationsView v-if="tab === 'advanced'" />
    <template v-else>
      <div class="permission-toolbar">
        <label class="permission-search">搜索模板或工具<input v-model.trim="query" type="search" placeholder="名称、用途或关联资源" /></label>
        <label v-if="tab === 'publications'">发布状态<select v-model="stateFilter"><option value="RUNNING">运行中</option><option value="OFFLINE">已下线</option><option value="">全部状态</option></select></label>
        <label v-if="tab === 'agents'">选择 Agent<select v-model="agentId"><option value="">请选择</option><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.label }}{{ agent.marketStatus !== 'published' ? '（尚未发布）' : '' }}</option></select></label>
      </div>
      <p class="publication-hint">{{ tab === 'templates' ? '仅展示模板授权与资产权限同时有效的模板。发布不会扩大底层资产权限。' : tab === 'agents' ? '仅已绑定且授权仍有效的工具可供 Agent 调用。解绑和下线即时限制后续调用。' : '每次发布固定模板版本和资源绑定；模板或资产变更后，需要重新确认发布。' }}</p>
      <div v-if="busy && !loaded" role="status" class="template-capability-empty">正在加载能力目录…</div>
      <div v-else-if="!visible.length" class="template-capability-empty" role="status">{{ tab === 'templates' ? '暂无可用模板，请联系管理员分配模板与资产权限。' : '暂无发布记录，先从可用模板中选择并发布。' }}</div>
      <div v-else class="template-capability-grid">
        <article v-for="item in visible" :key="item.key || item.id" class="template-capability-card">
          <div><span class="permission-badge">{{ tab === 'templates' ? item.category || '业务模板' : item.state === 'RUNNING' ? '运行中' : '已下线' }}</span><h2>{{ item.title }}</h2><p>{{ item.description || '按已授权的模板执行业务操作' }}</p></div>
          <dl><div><dt>关联资源</dt><dd>{{ item.assetName }}</dd></div><div><dt>{{ tab === 'templates' ? '参数' : '发布版本' }}</dt><dd>{{ tab === 'templates' ? parameterSummary(item) : `v${item.version}` }}</dd></div></dl>
          <p v-if="tab === 'templates'" class="publication-hint">所需操作权限：{{ item.operationLevel === 'manage' ? '管理' : item.operationLevel === 'write' ? '写入 / 执行' : '只读' }}</p>
          <details v-if="tab === 'templates' && Object.keys(item.inputSchema?.properties || {}).length"><summary>参数说明</summary><ul><li v-for="(parameter, name) in item.inputSchema.properties" :key="name"><strong>{{ parameter.title || name }}</strong> {{ parameter.description || parameter.type }}{{ item.inputSchema.required?.includes(name) ? '（必填）' : '' }}</li></ul></details>
          <footer>
            <button v-if="tab === 'templates' && canPublish" class="permission-primary" :disabled="busy" @click="selected = item">发布模板工具</button>
            <button v-else-if="tab === 'publications' && item.state === 'RUNNING' && canPublish" :disabled="busy" @click="offline(item)">下线工具</button>
            <label v-if="tab === 'agents'"><input type="checkbox" :checked="isBound(item)" :disabled="busy || !agentId || !canBind || !isBound(item) && item.state !== 'RUNNING'" @change="toggleBinding(item, $event.target.checked)" />{{ isBound(item) ? '已绑定当前 Agent' : '分配给当前 Agent' }}</label>
          </footer>
        </article>
      </div>
    </template>
    <div v-if="selected" class="permission-modal-backdrop">
      <form class="permission-profile-dialog" role="dialog" aria-modal="true" aria-label="确认发布模板工具" @submit.prevent="publish">
        <h2>确认发布模板工具</h2><p>{{ selected.title }}</p><p>关联资源：{{ selected.assetName }}</p><p class="publication-hint">发布当前模板版本，系统自动完成资源及执行绑定。重复发布相同版本不会创建重复工具。</p>
        <footer class="permission-form-actions"><button type="button" :disabled="busy" @click="selected = null">取消</button><button class="permission-primary" :disabled="busy">{{ busy ? '发布中…' : '确认发布' }}</button></footer>
      </form>
    </div>
  </section>
</template>
<script src="../js/views/TemplateToolsView.js"></script>
