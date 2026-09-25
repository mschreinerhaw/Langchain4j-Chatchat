<template>
  <section class="feature-view domain-intelligence-view">
    <header class="domain-intelligence-head">
      <p>Agent Runtime OS · 联合分析</p>
      <h1>联合分析</h1>
      <span>在同一工作台选择分析算力、知识和业务证据。Runtime 负责授权取证，通用模型或专业 Agent 负责分析。</span>
    </header>
    <form class="domain-intelligence-form" @submit.prevent="run">
      <section>
        <h2><span>1</span> 选择分析算力</h2>
        <p class="domain-hint">这里列出已发布的通用模型和已接入的专业 Agent。也可以从 Agent 管理中的卡片直接进入并预选。</p>
        <div class="domain-provider-list" role="group" aria-label="选择分析算力">
          <button v-for="provider in providers" :key="provider.providerId" type="button" class="domain-provider-card" :class="{ selected: form.providerId === provider.providerId }" :aria-pressed="form.providerId === provider.providerId" @click="selectProvider(provider.providerId)">
            <span class="domain-provider-mark">{{ provider.kind === 'GENERAL_LLM' ? '模' : '专' }}</span>
            <span class="domain-provider-copy"><strong>{{ provider.displayName || provider.providerId }}</strong><small>{{ providerKindLabel(provider) }} · {{ provider.professionalCapabilities?.length ? provider.professionalCapabilities.slice(0, 3).join('、') : '可用于证据分析' }}</small></span>
            <span class="domain-provider-state">{{ form.providerId === provider.providerId ? '已选择' : '选择' }}</span>
          </button>
        </div>
        <p v-if="!providers.length" class="domain-hint">当前暂无可用的分析算力，请联系管理员发布模型或接入并授权领域 Agent。</p>
        <div v-else-if="selectedProvider" class="domain-provider-detail">
          <span>可接收：{{ providerEvidenceSummary }}。实际使用范围以本次选择和调用用户权限为准。</span>
          <details v-if="selectedProvider.capabilities?.length > 1"><summary>选择专业分析能力</summary><select v-model="form.capability" aria-label="专业分析能力"><option v-for="capability in selectedProvider.capabilities" :key="capability" :value="capability">{{ capability }}</option></select></details>
        </div>
      </section>
      <section>
        <h2><span>2</span> 准备知识与证据</h2>
        <div class="domain-skill-search"><input v-model.trim="skillSearch" type="search" placeholder="按名称查找已发布的知识 Skill" @keyup.enter.prevent="loadOptions"><button type="button" :disabled="loading" @click="loadOptions">查找</button></div>
        <p class="domain-hint">最多选择 4 个 Skill；每个 Skill 的文档和工具独立授权、独立取证。</p>
        <div class="domain-skill-list"><label v-for="skill in selectableSkills" :key="skill.value" class="domain-checkbox"><input v-model="form.skillIds" type="checkbox" :value="skill.value" :disabled="form.skillIds.length >= 4 && !form.skillIds.includes(skill.value)" @change="changeSkills">{{ skill.label || skill.value }}</label></div>
        <div v-for="skill in selectedSkills" :key="skill.value" class="domain-skill-block">
          <strong>{{ skill.label || skill.value }}</strong>
          <div class="domain-resource-grid">
            <div><strong>文档知识</strong>
              <label v-for="id in documentsBySkill[skill.value] || []" :key="id" class="domain-checkbox"><input v-model="form.documentsBySkill[skill.value]" type="checkbox" :value="id" :disabled="!acceptsEvidence('DocumentAnalysisEvidence')">{{ id }}</label>
              <small v-if="!(documentsBySkill[skill.value] || []).length">暂无可选的授权文档。</small>
            </div>
            <div><strong>业务数据 / MCP 工具</strong>
              <label v-for="tool in availableTools.filter(item => item.skillId === skill.value)" :key="tool.key" class="domain-checkbox"><input v-model="form.selectedTools" type="checkbox" :value="tool.key" :disabled="!acceptsEvidence('ToolAnalysisEvidence')">{{ toolLabel(tool.toolName) }} <small>({{ tool.toolName }})</small></label>
              <small v-if="!availableTools.some(item => item.skillId === skill.value)">该 Skill 没有绑定工具。</small>
            </div>
          </div>
        </div>
        <button type="button" class="domain-advanced-toggle" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? '收起' : '高级：工具参数与只读数据模板' }} {{ advancedOpen ? '⌃' : '⌄' }}</button>
        <div v-if="advancedOpen" class="domain-advanced">
          <label v-for="key in form.selectedTools" :key="key">{{ key }} 的请求参数（JSON 对象）<textarea v-model="form.toolArguments[key]" rows="2" placeholder="{}"></textarea></label>
          <p class="domain-hint">工具将按勾选顺序由 Runtime 执行，最多 4 个；仅允许 Skill 已绑定的只读工具。</p>
          <div class="domain-fields">
            <label>已发布的只读 SQL 模板 ID<input v-model.trim="form.dataTemplateId" :disabled="!acceptsEvidence('StructuredDataEvidence')" placeholder="可选"></label>
            <label>模板所属 Skill<select v-model="form.dataSkillId"><option v-for="skill in selectedSkills" :key="skill.value" :value="skill.value">{{ skill.label || skill.value }}</option></select></label>
            <label>数据资产名称<input v-model.trim="form.dataAssetName" placeholder="使用模板时必填"></label>
            <label>环境<select v-model="form.dataEnvironment"><option value="">请选择</option><option v-for="env in ['DEV','TEST','UAT','PROD']" :key="env" :value="env">{{ env }}</option></select></label>
            <label>模板参数（JSON 对象）<textarea v-model="form.dataParameters" rows="2"></textarea></label>
          </div>
        </div>
      </section>
      <section>
        <h2><span>3</span> 分析要求与发送确认</h2>
        <label class="domain-full">希望本次分析回答什么？<textarea v-model="form.query" required rows="5" maxlength="4000" placeholder="例如：结合所选投资方法、持仓和交易数据，分析客户近一年的收益来源与风险特征。"></textarea></label>
        <div v-if="selectedProvider" class="domain-run-summary"><strong>本次分析</strong><span>{{ selectedProvider.displayName || selectedProvider.providerId }} · 已选择 {{ form.skillIds.length }} 个 Skill、{{ selectedEvidenceCount }} 项证据</span></div>
        <label class="domain-consent"><input v-model="form.confirmRemoteTransfer" type="checkbox">我确认将本次选中的文档片段与业务数据，在授权范围内发送给所选分析算力。</label>
      </section>
      <p v-if="error" class="domain-error" role="alert">{{ error }}</p>
      <button class="domain-submit" type="submit" :disabled="running || loading || !selectedProvider">{{ running ? '正在取证并分析…' : '开始联合分析' }}</button>
    </form>
    <section v-if="result" class="domain-result">
      <h2>分析结果 <small>{{ result.verification?.accepted ? '已完成证据校验' : '请查看校验说明' }}</small></h2>
      <p v-if="result.synthesis" class="domain-answer">{{ result.synthesis }}</p>
      <p v-else class="domain-hint">没有可展示的分析结论；请检查所选资源、执行状态和校验说明。</p>
      <details><summary>证据与校验详情</summary><p v-for="finding in result.verification?.findings || []" :key="finding">{{ finding }}</p><p>证据数量：{{ result.evidenceBundle?.evidence?.length || 0 }}</p></details>
    </section>
  </section>
</template>

<script src="../js/views/DomainIntelligenceView.js"></script>
