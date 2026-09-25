<template>
  <section class="feature-view domain-intelligence-view">
    <header class="domain-intelligence-head">
      <p>Agent Runtime OS · 分析编排</p>
      <h1>专有模型分析</h1>
      <span>由 Runtime 授权获取知识与业务数据，再交给集团或第三方专有 Agent 做专业分析。</span>
    </header>
    <form class="domain-intelligence-form" @submit.prevent="run">
      <section>
        <h2><span>1</span> 选择专有算力</h2>
        <div class="domain-fields">
          <label>集团 / 第三方 Agent
            <select v-model="form.providerId" required @change="changeProvider">
              <option value="">请选择已接入的专有模型</option>
              <option v-for="provider in providers" :key="provider.providerId" :value="provider.providerId">{{ provider.displayName || provider.providerId }} · {{ provider.origin === 'GROUP' ? '集团' : '第三方' }}</option>
            </select>
          </label>
          <label>分析能力
            <select v-model="form.capability" required>
              <option v-for="capability in selectedProvider?.capabilities || []" :key="capability" :value="capability">{{ capability }}</option>
            </select>
          </label>
        </div>
        <p v-if="!providers.length" class="domain-hint">当前租户暂无可用的专有算力，请联系管理员接入并授权。</p>
        <p v-else-if="selectedProvider" class="domain-hint">此模型获准接收的证据：{{ selectedProvider.evidenceTypes?.join('、') || '暂无' }}。若某类资源不可选，请联系管理员调整接入授权。</p>
      </section>
      <section>
        <h2><span>2</span> 选择知识与数据</h2>
        <div class="domain-skill-search"><input v-model.trim="skillSearch" type="search" placeholder="按名称查找已发布的知识 Skill" @keyup.enter.prevent="loadOptions"><button type="button" :disabled="loading" @click="loadOptions">查找</button></div>
        <label class="domain-full">知识 Skill
          <select v-model="form.skillId" required @change="changeSkill">
            <option value="">请选择已发布的 Skill</option>
            <option v-for="skill in skills" :key="skill.value" :value="skill.value">{{ skill.label || skill.value }}</option>
          </select>
        </label>
        <p class="domain-hint">选中 Skill 后，可勾选它绑定的文档、知识范围和 MCP 工具。Runtime 会先检查权限，再取证；不会向远端开放工具。</p>
        <div v-if="selectedSkill" class="domain-resource-grid">
          <div><strong>文档知识</strong>
            <label v-for="id in availableDocuments" :key="id" class="domain-checkbox"><input v-model="form.documentIds" type="checkbox" :value="id" :disabled="!acceptsEvidence('DocumentAnalysisEvidence')">{{ id }}</label>
            <small v-if="!availableDocuments.length">该 Skill 下暂无本次可选的授权文档。</small>
          </div>
          <div><strong>业务数据 / MCP 工具</strong>
            <label v-for="name in availableTools" :key="name" class="domain-checkbox"><input v-model="form.selectedTools" type="checkbox" :value="name" :disabled="!acceptsEvidence('ToolAnalysisEvidence')">{{ toolLabel(name) }} <small v-if="toolLabel(name) !== name">({{ name }})</small></label>
            <small v-if="!availableTools.length">该 Skill 没有绑定工具。</small>
          </div>
        </div>
        <button type="button" class="domain-advanced-toggle" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">{{ advancedOpen ? '收起' : '高级：工具参数与只读数据模板' }} {{ advancedOpen ? '⌃' : '⌄' }}</button>
        <div v-if="advancedOpen" class="domain-advanced">
          <label v-for="name in form.selectedTools" :key="name">{{ name }} 的请求参数（JSON 对象）<textarea v-model="form.toolArguments[name]" rows="2" placeholder="{}"></textarea></label>
          <p class="domain-hint">工具将按勾选顺序由 Runtime 执行，最多 4 个；仅允许 Skill 已绑定的只读工具。</p>
          <div class="domain-fields">
            <label>已发布的只读 SQL 模板 ID<input v-model.trim="form.dataTemplateId" :disabled="!acceptsEvidence('StructuredDataEvidence')" placeholder="可选"></label>
            <label>数据资产名称<input v-model.trim="form.dataAssetName" placeholder="使用模板时必填"></label>
            <label>环境<select v-model="form.dataEnvironment"><option value="">请选择</option><option v-for="env in ['DEV','TEST','UAT','PROD']" :key="env" :value="env">{{ env }}</option></select></label>
            <label>模板参数（JSON 对象）<textarea v-model="form.dataParameters" rows="2"></textarea></label>
          </div>
        </div>
      </section>
      <section>
        <h2><span>3</span> 填写分析要求</h2>
        <label class="domain-full">希望专有模型分析什么？<textarea v-model="form.query" required rows="5" maxlength="4000" placeholder="例如：结合所选投资方法、持仓和交易数据，分析客户近一年的收益来源与风险特征。"></textarea></label>
        <label class="domain-consent"><input v-model="form.confirmRemoteTransfer" type="checkbox">我确认将本次选中的文档片段与业务数据，在授权范围内发送给所选集团 / 第三方专有模型分析。</label>
      </section>
      <p v-if="error" class="domain-error" role="alert">{{ error }}</p>
      <button class="domain-submit" type="submit" :disabled="running || loading">{{ running ? '正在取证并分析…' : '执行分析' }}</button>
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
