<template>
  <div class="publication-definition" aria-label="模板与执行定义">
    <div class="publication-definition-summary">
      <article
        ><span class="publication-definition-icon"><Layers3 :size="19" /></span
        ><div
          ><span>执行能力</span><strong>{{ executionLabel }}</strong
          ><small>通过此能力查找受控模板</small></div
        ></article
      >
      <article
        ><span class="publication-definition-icon"><ShieldCheck :size="19" /></span
        ><div
          ><span>使用对象</span
          ><strong>{{ definition.subjectType === "USER" ? "指定用户" : roleLabel }}</strong
          ><small>调用时仍需满足模板和资源权限</small></div
        ></article
      >
      <article
        ><span class="publication-definition-icon"><Files :size="19" /></span
        ><div
          ><span>绑定模板</span><strong>{{ items.length }} 项</strong
          ><small>仅包含当前版本保存的模板范围</small></div
        ></article
      >
    </div>
    <section class="publication-definition-section">
      <header><h3>调用流程</h3></header>
      <ol class="publication-definition-flow" aria-label="工具调用流程">
        <li
          ><span>1</span><div><strong>接收调用参数</strong><p>按输入要求提供查询条件</p></div></li
        >
        <li
          ><span>2</span
          ><div><strong>按范围与权限匹配</strong><p>限定在已绑定且有权访问的模板内</p></div></li
        >
        <li
          ><span>3</span><div><strong>返回匹配结果</strong><p>供调用方选择可用模板</p></div></li
        >
      </ol>
    </section>
    <section class="publication-definition-section">
      <header
        ><div><h3>绑定的模板</h3><p>绑定范围来自当前版本；模板名称与可用信息来自当前目录。</p></div
        ><span class="publication-definition-count">{{ items.length }} 项</span></header
      >
      <p v-if="catalogState === 'loading'" class="publication-definition-notice" role="status"
        >正在读取模板名称与参数要求…</p
      >
      <div v-if="catalogState === 'error'" class="publication-definition-notice" role="status"
        ><span>模板目录暂时无法读取，当前版本的绑定范围已保留。</span
        ><button type="button" @click="$emit('retry')">重新加载</button></div
      >
      <div v-if="items.length" class="publication-definition-templates">
        <article
          v-for="(item, index) in items"
          :key="item.key"
          class="publication-definition-template"
        >
          <div class="publication-definition-template-heading"
            ><span class="publication-template-number">{{ index + 1 }}</span
            ><div
              ><h4>{{ item.title }}</h4
              ><div class="publication-definition-tags"
                ><span>{{ item.family }}</span
                ><span v-if="item.category">{{ item.category }}</span
                ><span v-if="!item.available && catalogState === 'ready'" class="is-unavailable"
                  >当前目录未找到</span
                ></div
              ></div
            ></div
          >
          <p v-if="item.description" class="publication-definition-description">{{
            item.description
          }}</p>
          <p
            v-else-if="catalogState === 'ready' && !item.available"
            class="publication-definition-description"
            >此模板仍在当前版本的绑定范围内，名称和参数要求暂不可用。</p
          >
          <details
            v-if="hasParameters(item.parameterSchema)"
            class="publication-template-parameters"
            ><summary>查看参数要求</summary><PublicationSchemaFields :schema="item.parameterSchema"
          /></details>
        </article>
      </div>
      <p v-else class="publication-definition-empty"
        >此版本尚未绑定模板。编辑草稿或创建新版本后可选择模板。</p
      >
    </section>
    <section class="publication-definition-section">
      <header
        ><div><h3>调用输入</h3><p>调用工具时需要提供的内容。</p></div></header
      >
      <PublicationSchemaFields
        :schema="inputSchema"
        empty-label="此版本未声明统一的调用字段，各模板的参数要求可在上方查看。"
      />
    </section>
    <section class="publication-definition-section">
      <header
        ><div><h3>返回内容</h3><p>此版本声明的返回字段。</p></div></header
      >
      <PublicationSchemaFields
        :schema="outputSchema"
        output
        empty-label="此版本未声明固定返回字段，具体内容由匹配到的模板提供。"
      />
    </section>
  </div>
</template>
<script src="../../js/components/publications/PublicationDefinition.js"></script>
<style src="../../styles/components/publications/publication-definition.css"></style>
