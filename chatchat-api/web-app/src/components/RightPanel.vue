<template>
  <div class="right-panel" :class="{ collapsed }">
    <section class="right-panel-shell">
      <div class="right-panel-head">
        <div>
          <strong>业务侧栏</strong>
          <span>持仓、风险、报告、任务与Agent</span>
        </div>
        <button
          type="button"
          :aria-label="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
          :title="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
          @click="$emit('toggle-collapsed')"
        >
          <PanelRightOpen v-if="collapsed" :size="18" stroke-width="1.8" />
          <PanelRightClose v-else :size="18" stroke-width="1.8" />
        </button>
      </div>

      <div v-if="collapsed" class="right-tool-rail">
        <button
          v-for="item in railItems"
          :key="item.id"
          type="button"
          :title="item.label"
          :aria-label="item.label"
          @click="$emit('toggle-collapsed')"
        >
          <component :is="item.icon" :size="18" stroke-width="2" />
          <span>{{ item.count }}</span>
        </button>
      </div>

      <template v-else>
        <section class="right-module">
          <header>
            <span class="right-module-title">
              <Briefcase :size="16" stroke-width="2" />
              <strong>我的持仓</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="holding in holdings" :key="holding.code" class="holding-row">
              <div>
                <strong>{{ holding.name }}</strong>
                <span>{{ holding.code }}</span>
              </div>
              <em :class="{ down: holding.change < 0 }">{{ holding.changeLabel }}</em>
            </article>
            <p v-if="holdings.length === 0" class="right-panel-empty">暂无持仓数据</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <AlertTriangle :size="16" stroke-width="2" />
              <strong>风险事件</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="risk in riskEvents" :key="risk.title" class="risk-row">
              <span class="risk-level" :class="risk.level">{{ risk.levelLabel }}</span>
              <div>
                <strong>{{ risk.title }}</strong>
                <time>{{ risk.time }}</time>
              </div>
            </article>
            <p v-if="riskEvents.length === 0" class="right-panel-empty">暂无风险事件</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <FileText :size="16" stroke-width="2" />
              <strong>最近报告</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="report in reports" :key="report.title" class="report-item">
              <span class="file-badge" :class="report.type">{{ report.mark }}</span>
              <div>
                <strong>{{ report.title }}</strong>
                <time>{{ report.time }}</time>
              </div>
            </article>
            <p v-if="reports.length === 0" class="right-panel-empty">暂无最近报告</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <Star :size="16" stroke-width="2" />
              <strong>收藏夹</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="favorite in favorites" :key="favorite.title" class="simple-row">
              <span class="favorite-star"></span>
              <strong>{{ favorite.title }}</strong>
              <time>{{ favorite.date }}</time>
            </article>
            <p v-if="favorites.length === 0" class="right-panel-empty">暂无收藏内容</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <ClipboardList :size="16" stroke-width="2" />
              <strong>待办任务</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="task in tasks" :key="task.title" class="task-row">
              <span class="task-dot" :class="task.status"></span>
              <strong>{{ task.title }}</strong>
              <span>{{ task.label }}</span>
            </article>
            <p v-if="tasks.length === 0" class="right-panel-empty">暂无任务</p>
            <button type="button" class="new-task">新建任务</button>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <Bot :size="16" stroke-width="2" />
              <strong>最近使用Agent</strong>
            </span>
            <button type="button">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="agent in recentAgents" :key="agent.id" class="agent-row">
              <span>{{ agent.shortName }}</span>
              <div>
                <strong>{{ agent.name }}</strong>
                <time>{{ agent.time }}</time>
              </div>
            </article>
            <p v-if="recentAgents.length === 0" class="right-panel-empty">暂无最近使用Agent</p>
          </div>
        </section>
      </template>
    </section>
  </div>
</template>

<script src="../js/components/RightPanel.js"></script>
