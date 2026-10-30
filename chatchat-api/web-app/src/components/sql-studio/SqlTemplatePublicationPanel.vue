<template>
  <Teleport to="body"
    ><div class="sql-dialog-backdrop" @mousedown.self="$emit('close')"
      ><section
        class="sql-dialog sql-publication-panel"
        role="dialog"
        aria-modal="true"
        aria-label="发布 MCP SQL 模板"
      >
        <header><h2>发布为 MCP 模板</h2><button type="button" class="sql-dialog-close" aria-label="关闭发布模板" title="关闭" @click="$emit('close')"><X :size="18" /></button></header>
        <p
          >{{ file.path }} · 保存版本 v{{ file.version }}。保存草稿不会执行
          SQL，也不会修改线上模板。</p
        >
        <p v-if="dirty" role="alert">有未保存修改，请先在 SQL Studio 保存。</p
        ><p v-if="error" role="alert">{{ error }}</p
        ><p aria-live="polite">{{ status }}</p>
        <label
          >草稿 / 历史修订<select v-model="selectedId" :disabled="busy"
            ><option value="">新模板</option
            ><option v-for="row in history" :key="row.id" :value="row.id"
              >v{{ row.version }} · {{ row.title }} · {{ row.state
              }}{{ row.active ? " · 当前线上" : "" }}</option
            ></select
          ></label
        >
        <fieldset :disabled="busy || published"
          ><legend>基本信息与参数</legend>
          <label>名称<input v-model="form.title" maxlength="200" /></label
          ><label>描述<textarea v-model="form.description" maxlength="1000" /></label
          ><label>分类<input v-model="form.category" /></label>
          <label
            >绑定数据源<select v-model="form.datasourceId"
              ><option value="">选择已授权数据源</option
              ><option v-for="source in sources" :key="source.id" :value="source.id"
                >{{ source.name }} · {{ source.databaseType }}</option
              ></select
            ></label
          >
          <label
            >参数 Schema<textarea
              v-model="form.schema"
              rows="8"
              aria-label="模板参数 Schema"
              spellcheck="false"
            />
          </label>
        </fieldset>
        <p
          >参数采用 <code v-pre>{{ name }}</code
          >。可选参数不自动删除条件，NULL 不作缺参默认值。测试支持单条只读查询，最长 10 秒、最多 50
          行。</p
        >
        <button :disabled="busy || dirty || !form.datasourceId" @click="createDraft"
          >从当前保存文件创建{{ selected ? "下一修订" : "草稿" }}</button
        >
        <button v-if="selected && !published" :disabled="busy || dirty" @click="saveDraft"
          >保存草稿并同步当前保存版本</button
        >
        <section v-if="selected"
          ><p
            >模板编码
            {{
              selected.code
            }}。若数据源启用了模板白名单，请先通过现有数据源管理入口批准此编码，之后才能测试和发布。</p
          ><h3>测试执行</h3
          ><p
            >源版本 v{{ selected.source?.fileVersion }} · SQL SHA-256：{{
              selected.source?.sqlDigest
            }}</p
          >
          <label
            >真实测试参数<textarea
              v-model="sample"
              rows="4"
              aria-label="模板测试参数"
              spellcheck="false"
              :disabled="busy"
            />
          </label>
          <button
            :disabled="busy || dirty || published || changed || !sourceCurrent"
            @click="perform('test')"
            >执行真实 SQL 测试</button
          >
          <p v-if="selected.testEvidence"
            >{{ selected.testEvidence.success ? "查询成功" : "查询失败" }} ·
            {{ selected.testEvidence.diagnostic }} · {{ selected.testEvidence.testedAt }} ·
            {{ selected.testEvidence.durationMs }} ms · 返回 {{ selected.testEvidence.rowCount ?? 0 }} 行 · 上限 {{ selected.testEvidence.maxRows }} 行</p
          >
          <p v-if="selected.testEvidence"
            >{{ selected.testEvidence.type }} · {{ selected.testEvidence.testId }} ·
            {{ selected.testEvidence.resultDigest }}</p
          >
          <p v-if="published && !sourceCurrent"
            >当前文件已更新，线上仍使用下方不可变 SQL 快照。修改须创建下一修订。</p
          >
          <p v-else-if="changed || !sourceCurrent"
            >保存版本或草稿参数已变化，请先同步草稿并重新测试。</p
          >
          <h3>发布前校验</h3>
          <ul aria-label="发布前校验状态">
            <li>文件已保存：{{ dirty ? '未通过' : '通过' }}</li>
            <li>来源版本一致：{{ sourceCurrent ? '通过' : '未通过' }}</li>
            <li>草稿配置已同步：{{ changed ? '未通过' : '通过' }}</li>
            <li>真实 SQL 测试：{{ selected.testEvidence?.success && sampleCurrent ? '通过' : '未通过' }}</li>
            <li>发布权限、参数契约和数据源授权：发布时由服务端重新校验</li>
          </ul>
          <h3>审核发布</h3
          ><label
            ><input v-model="reviewed" type="checkbox" :disabled="busy" />我已核对
            SQL、参数、数据源和真实测试证据，确认发布或恢复此修订。</label
          >
          <button v-if="!published" :disabled="busy || !canPublish" @click="perform('publish')"
            >确认发布 MCP 模板</button
          >
          <button
            v-else
            :disabled="busy || selected.active || !reviewed"
            @click="perform('rollback')"
            >重新测试并回滚 SQL / 参数 / 数据源</button
          >
          <p v-if="selected.publication"
            >MCP 工具 {{ selected.publication.toolName }} · 发布 v{{
              selected.publication.version
            }}
            · {{ selected.publication.state }}。Agent 授权仍通过现有能力管理入口明确绑定。</p
          >
          <h3>SQL 与发布历史</h3
          ><details
            ><summary>查看修订正文和参数</summary><pre>{{ selected.sqlTemplate }}</pre
            ><pre>{{ JSON.stringify(selected.parameterSchema, null, 2) }}</pre
            ><p>数据源 {{ selected.datasourceId }}</p></details
          >
          <details v-for="release in selected.releaseHistory || []" :key="release.publication.id"
            ><summary
              >发布 v{{ release.publication.version }} · {{ release.publication.state }}</summary
            ><pre>{{ release.sqlSnapshot.sqlTemplate }}</pre
            ><pre>{{ JSON.stringify(release.sqlSnapshot.parameterSchema, null, 2) }}</pre
            ><p
              >数据源 {{ release.sqlSnapshot.datasourceId }} · 实测
              {{ release.sqlTest.testedAt }}</p
            ></details
          >
        </section>
      </section></div
    ></Teleport
  >
</template>
<script src="../../js/components/sql-studio/SqlTemplatePublicationPanel.js"></script>
<style scoped src="../../styles/components/sql-studio/SqlTemplatePublicationPanel.css"></style>
