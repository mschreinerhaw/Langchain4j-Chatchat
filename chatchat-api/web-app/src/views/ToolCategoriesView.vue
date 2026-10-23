<template>
  <section class="permission-console tool-categories" :aria-busy="busy">
    <header class="permission-page-heading">
      <div
        ><nav aria-label="面包屑">MCP 工具发布 <span>/</span> 分类管理</nav><h1>分类管理</h1
        ><p>按业务用途整理工具，让团队更容易找到所需能力。</p></div
      >
      <div class="permission-heading-actions"
        ><button type="button" :disabled="busy" @click="refresh">刷新</button
        ><button
          v-if="editable"
          type="button"
          class="permission-primary"
          @click="open('create', null, $event)"
          >新建分类</button
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
    <div class="category-summary"
      ><div
        ><strong>{{ rows.length }}</strong
        ><span>业务分类</span></div
      ><div
        ><strong>{{ totalTools }}</strong
        ><span>已分类工具</span></div
      ><p>分类调整即时生效，工具的发布状态和历史版本不受影响。</p></div
    >
    <section class="category-panel" aria-label="业务分类列表">
      <div class="category-toolbar"
        ><div
          ><h2>全部分类</h2><span>共 {{ filtered.length }} 项</span></div
        ><label>搜索分类<input v-model="query" type="search" placeholder="输入分类名称" /></label
      ></div>
      <p v-if="busy && !loaded" class="category-empty" role="status">正在加载分类…</p>
      <div v-else-if="!filtered.length" class="category-empty"
        ><div class="category-empty-icon" aria-hidden="true">▤</div
        ><h3>{{ query ? "没有找到匹配的分类" : "从第一个业务分类开始" }}</h3
        ><p>{{
          query
            ? "试试其他关键词，或清空搜索查看全部分类。"
            : "例如客户分析、数据核验。未分配分类的工具仍可正常使用。"
        }}</p
        ><button v-if="query" type="button" @click="query = ''">清空搜索</button
        ><button
          v-else-if="editable"
          type="button"
          class="permission-primary"
          @click="open('create', null, $event)"
          >新建分类</button
        ></div
      >
      <div v-else class="category-table-wrap"
        ><table class="category-table"
          ><thead
            ><tr
              ><th scope="col">分类名称</th><th scope="col">关联工具</th
              ><th scope="col">操作</th></tr
            ></thead
          ><tbody
            ><tr v-for="row in visible" :key="row.id"
              ><td
                ><span class="category-folder" aria-hidden="true"></span
                ><strong>{{ row.name }}</strong></td
              ><td
                ><span class="category-count">{{ row.toolCount }} 个工具</span></td
              ><td
                ><div class="category-row-actions"
                  ><button
                    v-if="editable"
                    type="button"
                    :aria-label="`重命名 ${row.name}`"
                    @click="open('rename', row, $event)"
                    >重命名</button
                  ><button
                    v-if="editable"
                    type="button"
                    class="category-delete"
                    :aria-label="`删除 ${row.name}`"
                    @click="open('delete', row, $event)"
                    >删除</button
                  ><span v-if="!editable">仅查看</span></div
                ></td
              ></tr
            ></tbody
          ></table
        ></div
      >
      <footer v-if="filtered.length" class="permission-pagination category-pagination"
        ><span>共 {{ filtered.length }} 项 · 每页 10 项</span
        ><button type="button" :disabled="page <= 1" @click="page--">上一页</button
        ><span>{{ page }} / {{ pages }}</span
        ><button type="button" :disabled="page >= pages" @click="page++">下一页</button></footer
      >
    </section>
    <p class="category-footnote"
      >“未分类”为系统默认分组，无需创建。可在工具发布页直接调整工具所属分类。</p
    >
    <dialog
      v-if="mode"
      ref="dialog"
      class="category-dialog"
      aria-labelledby="category-dialog-title"
      @cancel.prevent="close"
    >
      <form @submit.prevent="submit">
        <header
          ><h2 id="category-dialog-title">{{
            mode === "delete" ? "删除分类" : mode === "rename" ? "重命名分类" : "新建分类"
          }}</h2
          ><button type="button" aria-label="关闭对话框" :disabled="saving" @click="close"
            >×</button
          ></header
        >
        <template v-if="mode === 'delete'"
          ><p class="category-dialog-lead">确定删除“{{ selected.name }}”？</p
          ><div class="category-impact"
            ><strong>{{
              selected.toolCount ? `此分类关联 ${selected.toolCount} 个工具` : "此分类暂无关联工具"
            }}</strong
            ><p>{{
              selected.toolCount
                ? "请选择这些工具的新归属。工具不会被删除，已发布的能力仍可使用。"
                : "删除后将不再出现在分类选项中。"
            }}</p></div
          ><label v-if="selected.toolCount"
            >将关联工具移至<select
              v-model="targetId"
              aria-label="关联工具的新分类"
              :disabled="saving"
              ><option value="">未分类</option
              ><option v-for="row in targets" :key="row.id" :value="row.id">{{
                row.name
              }}</option></select
            ></label
          ></template
        >
        <template v-else
          ><p class="category-dialog-description">{{
            mode === "rename"
              ? "新名称会同步显示在所有关联工具上。"
              : "使用团队熟悉的业务名称，方便查找和选择工具。"
          }}</p
          ><label
            >分类名称<input
              v-model="name"
              required
              maxlength="64"
              autofocus
              :disabled="saving"
              placeholder="例如：客户分析"
              :aria-invalid="!!dialogError"
              aria-describedby="category-form-error" /></label
          ><small>{{ name.length }} / 64</small></template
        >
        <div v-if="dialogError" id="category-form-error" class="category-dialog-error" role="alert"
          >{{ dialogError
          }}<button type="button" :disabled="busy || saving" @click="reloadDialog"
            >刷新分类信息</button
          ></div
        >
        <footer
          ><button type="button" :disabled="saving" @click="close">取消</button
          ><button
            type="submit"
            :class="mode === 'delete' ? 'category-danger' : 'permission-primary'"
            :disabled="saving || (mode !== 'delete' && !name.trim())"
            >{{
              saving
                ? "正在保存…"
                : mode === "delete"
                  ? selected.toolCount
                    ? "转移工具并删除"
                    : "确认删除"
                  : "保存"
            }}</button
          ></footer
        >
      </form>
    </dialog>
  </section>
</template>
<script src="../js/views/ToolCategoriesView.js"></script>
