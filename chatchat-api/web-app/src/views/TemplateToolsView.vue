<template>
  <section class="permission-console template-capability-workspace" :aria-busy="busy">
    <header class="permission-page-heading">
      <div
        ><nav>MCP 工具发布 / 能力管理</nav><h1>MCP 工具发布</h1
        ><p>从业务模板创建工具，发布后分配给 Agent 使用。</p></div
      >
      <div class="permission-heading-actions"
        ><button :disabled="busy" @click="load">刷新</button
        ><button
          v-if="canPublish"
          class="permission-primary"
          :disabled="busy"
          @click="navigate('templates')"
          >新建 MCP 工具</button
        ></div
      >
    </header>
    <p
      v-if="notice"
      class="permission-notice"
      :class="{ error: failed }"
      :role="failed ? 'alert' : 'status'"
      >{{ notice }}</p
    >
    <ol class="capability-journey" aria-label="工具创建与使用流程">
      <li :class="{ active: tab === 'templates' }"
        ><button @click="navigate('templates')"
          ><span>1</span><strong>选择模板</strong
          ><small>{{ templates.length }} 项可用能力 · 从这里新建</small></button
        ></li
      >
      <li :class="{ active: tab === 'publications' }"
        ><button @click="navigate('publications')"
          ><span>2</span><strong>发布工具</strong
          ><small>{{ runningCount }} 项运行中 · 查看版本与状态</small></button
        ></li
      >
      <li :class="{ active: tab === 'agents' }"
        ><button @click="navigate('agents')"
          ><span>3</span><strong>分配 Agent</strong
          ><small>选择 Agent 并绑定已发布工具</small></button
        ></li
      >
    </ol>
    <nav class="permission-category-tabs" aria-label="模板工具页面">
      <button
        v-for="item in tabs"
        :key="item.id"
        :class="{ active: tab === item.id }"
        @click="navigate(item.id)"
        >{{ item.label }}</button
      >
      <button v-if="advanced" :class="{ active: tab === 'advanced' }" @click="tab = 'advanced'"
        >历史版本管理</button
      >
    </nav>
    <ToolPublicationsView v-if="tab === 'advanced'" />
    <template v-else>
      <div class="permission-toolbar">
        <label class="permission-search"
          >搜索模板或工具<input
            v-model.trim="query"
            type="search"
            placeholder="名称、用途或关联资源"
        /></label>
        <label
          >业务分类<select v-model="category" aria-label="业务分类"
            ><option value="">全部分类</option
            ><option v-for="name in categories" :key="name" :value="name">{{
              name
            }}</option></select
          ></label
        >
        <label v-if="tab === 'publications'"
          >发布状态<select v-model="stateFilter"
            ><option value="RUNNING">运行中</option
            ><option value="OFFLINE">已下线</option
            ><option value="">全部状态</option></select
          ></label
        >
        <label v-if="tab === 'agents'"
          >选择 Agent<select v-model="agentId"
            ><option value="">请选择</option
            ><option v-for="agent in agents" :key="agent.id" :value="agent.id"
              >{{ agent.label
              }}{{ agent.marketStatus !== "published" ? "（尚未发布）" : "" }}</option
            ></select
          ></label
        >
      </div>
      <p class="publication-hint">{{
        tab === "templates"
          ? "仅展示模板授权与资产权限同时有效的模板。发布不会扩大底层资产权限。"
          : tab === "agents"
            ? "仅已绑定且授权仍有效的工具可供 Agent 调用。解绑和下线即时限制后续调用。"
            : "每次发布固定模板版本和资源绑定；模板或资产变更后，需要重新确认发布。"
      }}</p>
      <div v-if="busy && !loaded" role="status" class="template-capability-empty"
        >正在加载能力目录…</div
      >
      <div v-else-if="!visible.length" class="template-capability-empty" role="status"
        ><h2>{{
          query || category
            ? "没有匹配的结果"
            : tab === "templates"
              ? "暂无可用模板"
              : "暂无发布记录"
        }}</h2
        ><p>{{
          query || category
            ? "请调整搜索或分类条件。"
            : tab === "templates"
              ? "请先在 MCP 服务端维护模板，并分配模板与资产权限。"
              : "点击“新建 MCP 工具”，从可用模板中选择并发布。"
        }}</p
        ><button
          v-if="query || category"
          @click="
            query = '';
            category = '';
          "
          >清除筛选</button
        ><button
          v-else-if="tab !== 'templates' && canPublish"
          class="permission-primary"
          @click="navigate('templates')"
          >新建 MCP 工具</button
        ></div
      >
      <div v-else class="template-capability-grid">
        <article
          v-for="item in visible"
          :key="item.key || item.id"
          class="template-capability-card"
        >
          <div
            ><span class="permission-badge">{{
              tab === "templates"
                ? currentRelease(item)
                  ? "当前版本已发布"
                  : "待发布"
                : item.state === "RUNNING"
                  ? "运行中"
                  : "已下线"
            }}</span
            ><h2>{{ item.title }}</h2
            ><p>{{ item.description || "按已授权的模板执行业务操作" }}</p></div
          >
          <p class="publication-hint">业务分类：{{ categoryOf(item) }}</p>
          <dl
            ><div
              ><dt>关联资源</dt><dd>{{ item.assetName }}</dd></div
            ><div
              ><dt>{{ tab === "templates" ? "参数" : "发布版本" }}</dt
              ><dd>{{ tab === "templates" ? parameterSummary(item) : `v${item.version}` }}</dd></div
            ></dl
          >
          <p v-if="tab === 'templates'" class="publication-hint"
            >所需操作权限：{{
              item.operationLevel === "manage"
                ? "管理"
                : item.operationLevel === "write"
                  ? "写入 / 执行"
                  : "只读"
            }}</p
          >
          <details
            v-if="tab === 'templates' && Object.keys(item.inputSchema?.properties || {}).length"
            ><summary>参数说明</summary
            ><ul
              ><li v-for="(parameter, name) in item.inputSchema.properties" :key="name"
                ><strong>{{ parameter.title || name }}</strong>
                {{ parameter.description || parameter.type
                }}{{ item.inputSchema.required?.includes(name) ? "（必填）" : "" }}</li
              ></ul
            ></details
          >
          <footer>
            <button
              v-if="tab === 'templates' && currentRelease(item) && canBind"
              class="permission-primary"
              :disabled="busy"
              @click="assign(currentRelease(item))"
              >分配给 Agent</button
            >
            <button
              v-else-if="tab === 'templates' && canPublish"
              class="permission-primary"
              :disabled="busy"
              @click="selected = item"
              >发布模板工具</button
            >
            <button
              v-if="tab === 'publications' && item.state === 'RUNNING' && canBind"
              class="permission-primary"
              :disabled="busy"
              @click="assign(item)"
              >分配给 Agent</button
            >
            <button
              v-if="tab === 'publications' && item.state === 'RUNNING' && canPublish"
              :disabled="busy"
              @click="offline(item)"
              >下线工具</button
            >
            <label v-if="tab === 'agents'"
              ><input
                type="checkbox"
                :checked="isBound(item)"
                :disabled="
                  busy || !agentId || !canBind || (!isBound(item) && item.state !== 'RUNNING')
                "
                @change="toggleBinding(item, $event.target.checked)"
              />{{ isBound(item) ? "已绑定当前 Agent" : "分配给当前 Agent" }}</label
            >
          </footer>
        </article>
      </div>
      <footer v-if="loaded" class="capability-pagination" aria-label="工具分页"
        ><span>共 {{ filtered.length }} 项</span
        ><label
          >每页<select v-model.number="pageSize" aria-label="每页条数"
            ><option :value="12">12 项</option
            ><option :value="24">24 项</option
            ><option :value="48">48 项</option></select
          ></label
        ><div
          ><button :disabled="page <= 1 || busy" @click="page--">上一页</button
          ><span aria-live="polite">{{ page }} / {{ pages }}</span
          ><button :disabled="page >= pages || busy" @click="page++">下一页</button></div
        ></footer
      >
      <aside class="capability-help"
        ><strong>{{
          tab === "templates"
            ? "没有合适的模板？"
            : tab === "agents"
              ? "绑定后如何使用？"
              : "需要修改工具？"
        }}</strong
        ><p
          >{{
            tab === "templates"
              ? "管理员在 MCP 服务端的 API、查询或资产执行模板中维护能力，完成测试并启用后，按模板与资产权限进入此目录。"
              : tab === "agents"
                ? "在 Agent 工作空间发起业务问题。只有已绑定、处于运行中且权限有效的模板工具可被调用。"
                : "更新源模板后，从“可用模板”确认发布新版本；旧版本保留在已下线记录中。"
          }}<template v-if="advanced && tab === 'publications'"
            >历史工具草稿位于“历史版本管理”。</template
          ></p
        ></aside
      >
    </template>
    <div v-if="selected" class="permission-modal-backdrop">
      <form
        class="permission-profile-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="确认发布模板工具"
        @submit.prevent="publish"
      >
        <h2>确认发布模板工具</h2><p>{{ selected.title }}</p
        ><p>业务分类：{{ categoryOf(selected) }}</p
        ><p>关联资源：{{ selected.assetName }}</p
        ><p class="publication-hint"
          >发布当前模板版本，系统自动完成资源及执行绑定。发布成功后，请在“Agent
          工具”中分配使用范围。</p
        ><p class="publication-hint"
          >重复发布相同版本不会创建重复工具；发布不会扩大底层资产权限。</p
        >
        <footer class="permission-form-actions"
          ><button type="button" :disabled="busy" @click="selected = null">取消</button
          ><button class="permission-primary" :disabled="busy">{{
            busy ? "发布中…" : "确认发布"
          }}</button></footer
        >
      </form>
    </div>
  </section>
</template>
<script src="../js/views/TemplateToolsView.js"></script>
