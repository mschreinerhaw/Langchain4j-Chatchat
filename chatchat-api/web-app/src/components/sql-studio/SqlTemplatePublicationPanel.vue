<template>
  <Teleport to="body">
    <div class="sql-dialog-backdrop" @mousedown.self="!busy && $emit('close')">
      <section
        class="sql-dialog sql-publication-panel"
        role="dialog"
        aria-modal="true"
        aria-label="发布 MCP SQL 模板"
      >
        <header
          ><h2>发布查询模板</h2
          ><button
            type="button"
            class="sql-dialog-close"
            aria-label="关闭发布模板"
            :disabled="busy"
            title="关闭"
            @click="$emit('close')"
            ><X :size="18" /></button
        ></header>
        <div class="sql-publication-body">
          <p>配置模板信息，发布后可在 Agent 中授权使用。</p>
          <p v-if="dirty" role="alert">有未保存修改，请先保存 SQL 文件。</p>
          <p v-if="error" role="alert">{{ error }}</p>
          <p v-if="status" aria-live="polite">{{ status }}</p>
          <label
            >模板版本<select v-model="selectedId" :disabled="busy"
              ><option value="">新模板</option
              ><option v-for="row in history" :key="row.id" :value="row.id"
                >{{ row.title }} ·
                {{
                  row.active
                    ? "当前已发布"
                    : row.state === "PUBLISHED"
                      ? "已下线 / 历史版本"
                      : "草稿"
                }}</option
              ></select
            ></label
          >
          <fieldset :disabled="busy">
            <legend>基本信息</legend>
            <label>名称<input v-model="form.title" maxlength="200" /></label>
            <label>描述<textarea v-model="form.description" maxlength="1000" rows="2" /></label>
            <label
              >业务分类<select v-model="form.category" aria-label="模板业务分类"
                ><option value="" disabled>请选择业务分类</option
                ><option v-if="legacyCategory" :value="form.category" disabled
                  >{{ form.category }}（原分类已不可用，请重新选择）</option
                ><option
                  v-for="category in categories"
                  :key="category.code"
                  :value="category.code"
                  >{{ category.name }}</option
                ></select
              ></label
            >
            <p v-if="!busy && !categories.length" class="sql-publication-hint"
              >暂无可用业务分类，请联系管理员在 MCP Server 的业务分类维护中配置。</p
            >
            <label
              >绑定数据源<select v-model="form.datasourceId" :disabled="published"
                ><option value="">选择已授权数据源</option
                ><option v-for="source in sources" :key="source.id" :value="source.id">{{
                  source.name
                }}</option></select
              ></label
            >
            <details class="sql-publication-technical"
              ><summary>参数定义与 SQL 说明</summary>
              <label
                >参数 Schema<textarea
                  v-model="form.schema"
                  rows="6"
                  aria-label="模板参数 Schema"
                  spellcheck="false"
                  :disabled="published"
                />
              </label>
              <p
                >参数采用 <code v-pre>{{ name }}</code
                >，占位符外不要添加引号。可选参数需要 NULL
                时须声明可空类型。测试只执行单条只读查询，最长 10 秒、最多 50 行。</p
              >
              <p
                >{{ file.path }} · 文件版本 v{{ file.version }}。保存草稿不会执行
                SQL，也不会修改线上模板。</p
              >
            </details>
          </fieldset>
          <div class="sql-publication-draft-actions">
            <button
              type="button"
              :disabled="busy || dirty || !form.datasourceId || legacyCategory"
              @click="createDraft"
              >{{
                published ? "修改 SQL 或参数（创建新修订）" : selected ? "另存为新修订" : "保存草稿"
              }}</button
            >
            <button
              v-if="selected && !published"
              type="button"
              :disabled="busy || dirty || legacyCategory"
              @click="saveDraft"
              >保存草稿</button
            >
          </div>
          <section v-if="selected">
            <h3>查询测试</h3>
            <label
              >测试参数<textarea
                v-model="sample"
                rows="3"
                aria-label="模板测试参数"
                spellcheck="false"
                :disabled="busy"
              />
            </label>
            <button
              v-if="!published"
              type="button"
              :disabled="busy || dirty || changed || !sourceCurrent"
              @click="perform('test')"
              >执行真实 SQL 测试</button
            >
            <p v-if="selected.testEvidence"
              >{{ selected.testEvidence.success ? "查询成功" : "查询失败" }} · 返回
              {{ selected.testEvidence.rowCount ?? 0 }} 行</p
            >
            <section
              v-if="selected.testEvidence?.success && selected.testEvidence.preview"
              aria-label="真实查询结果预览"
            >
              <h3>查询结果预览</h3>
              <p v-if="selected.testEvidence.preview.possiblyTruncated"
                >查询达到返回上限，结果可能不完整，请缩小业务范围后重试。</p
              >
              <p v-if="selected.testEvidence.preview.previewTruncated"
                >此处仅展示部分查询结果，不能据此认定完整业务对账通过。</p
              >
              <div class="sql-test-result-preview"
                ><table
                  ><thead
                    ><tr
                      ><th v-for="column in selected.testEvidence.preview.columns" :key="column">{{
                        column
                      }}</th></tr
                    ></thead
                  ><tbody
                    ><tr v-for="(row, index) in selected.testEvidence.preview.rows" :key="index"
                      ><td v-for="column in selected.testEvidence.preview.columns" :key="column">{{
                        row[column] === null ? "NULL" : row[column]
                      }}</td></tr
                    ></tbody
                  ></table
                ></div
              >
              <p v-if="!selected.testEvidence.preview.returnedRowCount"
                >查询成功，未返回记录。空结果不代表业务链路已通过验收。</p
              >
            </section>
            <p v-if="published && !sourceCurrent" class="sql-publication-hint"
              >SQL 文件已修改，请创建新修订后发布；当前线上查询保持原版本。</p
            >
            <details class="sql-publication-technical" aria-label="发布技术详情">
              <summary>发布校验与技术详情</summary>
              <h3>发布前校验</h3>
              <ul aria-label="发布前校验状态"
                ><li>文件已保存：{{ dirty ? "未通过" : "通过" }}</li
                ><li>来源版本一致：{{ sourceCurrent ? "通过" : "未通过" }}</li
                ><li>草稿配置已同步：{{ changed ? "发布时同步" : "通过" }}</li
                ><li
                  >真实 SQL 测试：{{
                    selected.testEvidence?.success && sampleCurrent ? "通过" : "发布时执行"
                  }}</li
                ><li>发布权限、参数契约和数据源授权由服务端重新校验</li></ul
              >
              <p
                >模板编码
                {{
                  selected.code
                }}。若数据源启用了模板白名单，首次执行前需通过现有数据源管理批准此编码。</p
              >
              <p
                >源版本 v{{ selected.source?.fileVersion }} · SQL SHA-256：{{
                  selected.source?.sqlDigest
                }}</p
              >
              <p v-if="selected.testEvidence"
                >{{ selected.testEvidence.type }} · {{ selected.testEvidence.testId }} ·
                {{ selected.testEvidence.diagnostic }} · {{ selected.testEvidence.testedAt }} ·
                {{ selected.testEvidence.durationMs }} ms ·
                {{ selected.testEvidence.resultDigest }}</p
              >
              <p v-if="selected.publication"
                >MCP 工具 {{ selected.publication.toolName }} · 发布 v{{
                  selected.publication.version
                }}
                · {{ selected.publication.state }}。Agent 授权仍通过现有能力管理入口绑定。</p
              >
              <h3>SQL 与发布历史</h3>
              <details
                ><summary>查看修订正文和参数</summary><pre>{{ selected.sqlTemplate }}</pre
                ><pre>{{ JSON.stringify(selected.parameterSchema, null, 2) }}</pre
                ><p>数据源 {{ selected.datasourceId }}</p></details
              >
              <details
                v-for="release in selected.releaseHistory || []"
                :key="release.publication.id"
                ><summary
                  >发布 v{{ release.publication.version }} ·
                  {{ release.publication.state }}</summary
                ><p
                  >{{ release.sqlSnapshot.title }} · {{ release.sqlSnapshot.description }} ·
                  {{ release.sqlSnapshot.category }}</p
                ><pre>{{ release.sqlSnapshot.sqlTemplate }}</pre
                ><pre>{{ JSON.stringify(release.sqlSnapshot.parameterSchema, null, 2) }}</pre
                ><p
                  >数据源 {{ release.sqlSnapshot.datasourceId }} · 实测
                  {{ release.sqlTest.testedAt }}</p
                ></details
              >
              <button
                v-if="published"
                type="button"
                :disabled="busy || selected.active || !reviewed"
                @click="perform('rollback')"
                >重新测试并回滚 SQL / 参数 / 数据源</button
              >
            </details>
          </section>
        </div>
        <footer class="sql-publication-footer">
          <div
            ><label class="sql-publication-review"
              ><input v-model="reviewed" type="checkbox" :disabled="busy" /><span
                >我已核对模板信息和测试参数，确认发布。</span
              ></label
            ><p class="sql-publication-hint">{{ publicationHint }}</p></div
          >
          <div class="sql-publication-footer-actions"
            ><button type="button" :disabled="busy" @click="$emit('close')">取消</button
            ><button
              type="button"
              class="sql-publication-primary"
              :disabled="busy || !canPublish"
              @click="publishTemplate"
              >{{
                busy ? "处理中…" : published && selected.active ? "发布更新" : "发布模板"
              }}</button
            ></div
          >
        </footer>
      </section>
    </div>
  </Teleport>
</template>
<script src="../../js/components/sql-studio/SqlTemplatePublicationPanel.js"></script>
<style scoped src="../../styles/components/sql-studio/SqlTemplatePublicationPanel.css"></style>
