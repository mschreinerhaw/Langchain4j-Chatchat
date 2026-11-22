<template>
  <section class="business-workspace">
    <ChatAssistantView v-show="panel === 'chat'" ref="chat"
      :user-id="userId" :tenant-id="tenantId" :workspace-context="workspaceContext"
      :selected-conversation="selectedConversation" :pending-draft="draft || pendingDraft"
      :preferred-agent-id="defaultAgentId" slash-skill-picker business-mode
      @pick-skill="openPanel('skills')" @conversation-active="$emit('conversation-active', $event)"
      @history-saved="$emit('history-saved', $event)">
      <template #welcome>
        <div class="business-welcome">
          <div class="business-robot-scene" aria-hidden="true">
            <img src="../assets/business-robot-at-work.webp" alt="" decoding="async" />
            <span class="business-robot-monitor"></span>
          </div>
          <h1>{{ title || '业务智能助手' }}</h1>
          <p>{{ welcome || '随时提问、分析资料，或从左侧选择业务技能。' }}</p>
          <div v-if="questionList.length || recommendations.length" class="business-actions">
            <button v-for="question in questionList" :key="question" type="button" @click="prepareChat(question)">
              {{ question }}
            </button>
            <button v-for="skill in recommendations" :key="skill.id" type="button" @click="openSkill(skill)">
              {{ skill.name }}
            </button>
          </div>
        </div>
      </template>
    </ChatAssistantView>

    <section v-if="panel === 'skills'" class="business-panel business-catalog">
      <header class="business-catalog-banner">
      <h1>技能中心</h1>
      <p>查找当前账号有权使用的业务技能。</p>
      <form class="business-actions" @submit.prevent="loadSkills(0)">
        <input v-model="skillKeyword" type="search" placeholder="搜索名称或说明" aria-label="搜索技能" />
        <button type="submit" :disabled="skillsBusy">搜索</button>
      </form>
      </header>
      <div class="business-actions business-catalog-tabs">
        <button type="button" aria-pressed="true">技能</button>
        <button type="button" @click="knowledgeTab = 'bases'; openPanel('knowledge')">知识库</button>
      </div>
      <p v-if="skillError" role="alert">{{ skillError }}</p>
      <p v-if="skillsBusy" role="status">正在查询授权技能…</p>
      <p v-else-if="!skills.length && !skillError">暂无匹配的已授权技能。</p>
      <div class="business-grid">
        <article v-for="skill in skills" :key="skill.id" class="business-card business-catalog-card">
          <span class="business-catalog-icon"><Sparkles :size="24" aria-hidden="true" /></span>
          <h2>{{ skill.name }}</h2>
          <small>{{ skill.category || '领域技能' }}</small>
          <p>{{ skill.description || '暂无简介' }}</p>
          <details>
            <summary>查看适用场景和示例</summary>
            <div v-for="agent in eligibleAgents(skill)" :key="agent.id">
              <p v-for="scenario in agent.usageScenarios || []" :key="scenario">{{ scenario }}</p>
              <button v-for="example in agent.quickQuestions || []" :key="example" type="button"
                @click="prepareSkill(skill, agent, example)">{{ example }}</button>
            </div>
            <p v-if="!eligibleAgents(skill).length">暂无已授权且绑定此技能的 Agent。</p>
          </details>
          <button v-for="agent in eligibleAgents(skill)" :key="agent.id" type="button"
            @click="prepareSkill(skill, agent)">通过 {{ agent.name }} 发起对话</button>
        </article>
      </div>
      <div v-if="skillPages > 1" class="business-actions">
        <button type="button" :disabled="skillsBusy || skillPage === 0" @click="loadSkills(skillPage - 1)">上一页</button>
        <span>{{ skillPage + 1 }} / {{ skillPages }}</span>
        <button type="button" :disabled="skillsBusy || skillPage + 1 >= skillPages" @click="loadSkills(skillPage + 1)">下一页</button>
      </div>
    </section>

    <section v-if="panel === 'guides'" class="business-panel">
      <h1>岗位工作</h1>
      <BusinessUserNotes :key="'guides:' + userId + ':' + tenantId" panel="guides" :workspace-context="workspaceContext" />
      <details class="business-note-secondary">
      <summary>角色说明与岗位指引</summary>
      <BusinessRoleNotes :key="'guides:' + userId + ':' + tenantId" panel="guides" :workspace-context="workspaceContext" />
      <p v-if="!guideList.length">当前部门暂无岗位指引。</p>
      <article v-for="guide in guideList" :key="guide" class="business-card">
        <h2>{{ guide }}</h2>
        <button type="button" @click="prepareChat('请根据我有权限的业务资料，指导我完成：' + guide)">发起提问</button>
      </article>
      </details>
    </section>

    <section v-if="panel === 'knowledge'" class="business-panel business-catalog">
      <header class="business-catalog-banner">
      <h1>知识中心</h1>
      <p>检索已授权的知识库和文档。</p>
      <form v-if="knowledgeTab === 'bases'" class="business-actions" @submit.prevent="loadBases(0)">
        <input v-model="baseKeyword" type="search" placeholder="搜索知识库名称" aria-label="搜索知识库" />
        <button type="submit" :disabled="basesBusy">搜索</button>
      </form>
      <form v-else class="business-actions" @submit.prevent="loadKnowledge(1)">
        <input v-model="knowledgeQuery" type="search" placeholder="检索已授权文档" aria-label="检索业务知识" />
        <select v-if="categoryList.length || knowledgeCategory" v-model="knowledgeCategory" aria-label="知识分类" @change="loadKnowledge(1)">
          <option value="">全部分类</option>
          <option v-if="knowledgeCategory && !categoryList.includes(knowledgeCategory)">{{ knowledgeCategory }}</option>
          <option v-for="category in categoryList" :key="category">{{ category }}</option>
        </select>
        <button type="submit" :disabled="knowledgeBusy">检索</button>
      </form>
      </header>
      <div class="business-actions business-catalog-tabs" role="group" aria-label="知识资源">
        <button type="button" :aria-pressed="knowledgeTab === 'bases'" @click="openKnowledge('bases')">知识库</button>
        <button type="button" :aria-pressed="knowledgeTab === 'documents'" @click="openKnowledge('documents')">文档</button>
      </div>
      <template v-if="knowledgeTab === 'bases'">
        <p v-if="baseError" role="alert">{{ baseError }}</p>
        <p v-if="basesBusy" role="status">正在查询授权知识库…</p>
        <p v-else-if="!bases.length && !baseError">暂无匹配的已授权知识库。</p>
        <div class="business-grid">
          <article v-for="base in bases" :key="base.id" class="business-card business-catalog-card">
            <span class="business-catalog-icon blue"><BookOpen :size="24" aria-hidden="true" /></span>
            <h2>{{ base.name }}</h2>
            <p>{{ base.documentCount }} 份可见文档</p>
            <button type="button" @click="browseBase(base)">查看文档</button>
          </article>
        </div>
        <div v-if="basePages > 1" class="business-actions">
          <button type="button" :disabled="basesBusy || basePage === 0" @click="loadBases(basePage - 1)">上一页</button>
          <span>{{ basePage + 1 }} / {{ basePages }}</span>
          <button type="button" :disabled="basesBusy || basePage + 1 >= basePages" @click="loadBases(basePage + 1)">下一页</button>
        </div>
      </template>
      <template v-else>
      <p v-if="knowledgeError" role="alert">{{ knowledgeError }}</p>
      <p v-if="knowledgeBusy" role="status">正在检索知识资源…</p>
      <p v-else-if="!knowledge.length && !knowledgeError">暂无匹配的已授权文档。</p>
      <div class="business-grid">
      <article v-for="document in knowledge" :key="document.docId" class="business-card business-catalog-card">
        <span class="business-catalog-icon blue"><FileText :size="24" aria-hidden="true" /></span>
        <h2>{{ document.title }}</h2>
        <small>{{ [document.source, document.date].filter(Boolean).join(' · ') }}</small>
        <p>{{ document.summary }}</p>
        <button type="button" @click="prepareChat('请查找授权资料《' + document.title + '》，说明其要点并标明来源。')">
          从此资料发起提问
        </button>
      </article>
      </div>
      <div v-if="knowledgePage > 1 || knowledgeHasMore" class="business-actions">
        <button type="button" :disabled="knowledgeBusy || knowledgePage === 1" @click="loadKnowledge(knowledgePage - 1)">上一页</button>
        <button type="button" :disabled="knowledgeBusy || !knowledgeHasMore" @click="loadKnowledge(knowledgePage + 1)">下一页</button>
      </div>
      </template>
    </section>

    <section v-if="panel === 'outcomes'" class="business-panel">
      <h1>工作成果</h1>
      <BusinessUserNotes :key="'outcomes:' + userId + ':' + tenantId" panel="outcomes" :workspace-context="workspaceContext" />
      <details class="business-note-secondary">
      <summary>角色说明与会话成果</summary>
      <BusinessRoleNotes :key="'outcomes:' + userId + ':' + tenantId" panel="outcomes" :workspace-context="workspaceContext" />
      <p v-if="!outcomeHistory.length">暂无业务会话记录。</p>
      <article v-for="item in outcomeHistory" :key="item.id" class="business-card business-outcome">
        <div><h2>{{ item.title || '业务会话' }}</h2><small>{{ outcomeStatus(item.status) }}</small></div>
        <button type="button" @click="openOutcome(item)">打开会话与结果</button>
      </article>
      </details>
    </section>
  </section>
</template>

<script src="../js/views/BusinessWorkspaceView.js"></script>
<style src="../styles/pages/business-workspace.css"></style>
