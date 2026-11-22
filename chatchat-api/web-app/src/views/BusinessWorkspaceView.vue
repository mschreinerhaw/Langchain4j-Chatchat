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
      <nav v-if="skillPages > 0 && !skillError" class="business-actions business-catalog-pagination" aria-label="技能分页">
        <button type="button" :disabled="skillsBusy || skillPage === 0" @click="loadSkills(skillPage - 1)">上一页</button>
        <span aria-live="polite">第 {{ skillPage + 1 }} / {{ skillPages }} 页</span>
        <button type="button" :disabled="skillsBusy || skillPage + 1 >= skillPages" @click="loadSkills(skillPage + 1)">下一页</button>
      </nav>
    </section>

    <section v-if="panel === 'guides'" class="business-panel">
      <h1>岗位工作</h1>
      <BusinessUserNotes :key="'guides:' + userId + ':' + tenantId" panel="guides" :workspace-context="workspaceContext" />
    </section>

    <section v-if="panel === 'knowledge'" class="business-panel business-catalog">
      <header class="business-catalog-banner">
        <h1>知识中心</h1>
        <p>检索已授权的文档。</p>
        <form class="business-actions" @submit.prevent="loadKnowledge(1)">
          <input v-model="knowledgeQuery" type="search" placeholder="检索已授权文档" aria-label="检索业务知识" />
          <select v-if="categoryList.length || knowledgeCategory" v-model="knowledgeCategory" aria-label="知识分类" @change="loadKnowledge(1)">
            <option value="">全部分类</option>
            <option v-if="knowledgeCategory && !categoryList.includes(knowledgeCategory)">{{ knowledgeCategory }}</option>
            <option v-for="category in categoryList" :key="category">{{ category }}</option>
          </select>
          <button type="submit" :disabled="knowledgeBusy">检索</button>
        </form>
      </header>
      <p v-if="knowledgeError" role="alert">{{ knowledgeError }}</p>
      <p v-if="knowledgeBusy" role="status">正在检索知识资源…</p>
      <p v-else-if="!knowledge.length && !knowledgeError">暂无匹配的已授权文档。</p>
      <div class="business-grid">
        <article v-for="document in knowledge" :key="document.docId" class="business-card business-catalog-card">
          <span class="business-catalog-icon blue"><FileText :size="24" aria-hidden="true" /></span>
          <h2>{{ document.title }}</h2>
          <small>{{ [document.source, document.date].filter(Boolean).join(' · ') }}</small>
          <p>{{ document.summary }}</p>
        </article>
      </div>
      <nav v-if="knowledgePages > 0 && !knowledgeError" class="business-actions business-catalog-pagination" aria-label="文档分页">
        <button type="button" :disabled="knowledgeBusy || knowledgePage <= 1" @click="loadKnowledge(knowledgePage - 1)">上一页</button>
        <span aria-live="polite">第 {{ knowledgePage }} / {{ knowledgePages }} 页</span>
        <button type="button" :disabled="knowledgeBusy || knowledgePage >= knowledgePages" @click="loadKnowledge(knowledgePage + 1)">下一页</button>
      </nav>
    </section>

    <section v-if="panel === 'outcomes'" class="business-panel">
      <h1>工作成果</h1>
      <BusinessUserNotes :key="'outcomes:' + userId + ':' + tenantId" panel="outcomes" :workspace-context="workspaceContext" />
    </section>
  </section>
</template>

<script src="../js/views/BusinessWorkspaceView.js"></script>
<style src="../styles/pages/business-workspace.css"></style>
