<script src="../js/components/TokenQuotaEditor.js"></script>

<template>
  <button v-if="personal" ref="trigger" type="button" class="ghost-button account-token-quota-trigger"
    aria-haspopup="dialog" @click="openDialog">个人 Token 额度</button>
  <Teleport to="body" :disabled="!personal">
    <component :is="personal ? 'dialog' : 'details'" ref="container"
      :class="personal ? 'entity-modal token-quota-dialog' : undefined"
      :aria-labelledby="personal ? headingId : undefined" :aria-busy="personal ? busy : undefined"
      @toggle="openRole" @cancel.prevent="closeDialog">
      <header v-if="personal" class="modal-head">
        <div><h2 :id="headingId">个人 Token 额度</h2></div>
        <button type="button" class="app-dialog-close" aria-label="关闭个人 Token 额度" :disabled="busy" @click="closeDialog">
          <X :size="18" />
        </button>
      </header>
      <summary v-else>角色共享 Token 额度</summary>
      <div :class="personal ? 'modal-entity-form token-quota-body' : undefined">
        <p v-if="busy && !pool" role="status">正在加载额度…</p>
        <p v-if="error" class="permission-notice error" role="alert">{{ error }}</p>
        <button v-if="personal && error && !pool" type="button" class="ghost-button" @click="loadQuota">重新加载</button>
        <p v-if="notice" class="permission-notice" role="status">{{ notice }}</p>
        <template v-if="pool">
          <p>周期 {{ pool.period }} · 已消费 {{ pool.spent }} · 已预留 {{ pool.reserved }}</p>
          <p v-if="pool.limit !== null">可用 {{ remaining }} · 已消费比例 {{ percentage }}</p>
          <p v-else>当前额度不限</p>
          <p>上次重置 {{ displayTime(history?.lastResetAt) }}</p>
          <div :class="personal ? 'entity-form' : 'permission-form-grid'">
            <label>额度方式
              <select v-model="unlimited" :disabled="busy">
                <option :value="true">不限额</option>
                <option :value="false">指定上限</option>
              </select>
            </label>
            <label v-if="!unlimited">Token 上限
              <input v-model.trim="limit" inputmode="numeric" :disabled="busy" @keydown.enter.prevent />
            </label>
          </div>
          <div v-if="!personal" class="modal-actions">
            <button type="button" class="ghost-button" :disabled="busy" @click="confirmReset = !confirmReset">重置周期</button>
            <button type="button" class="primary-button" :disabled="busy" @click="save(false)">保存额度</button>
          </div>
          <div v-if="confirmReset" :class="personal ? 'permission-notice' : undefined">
            <p>按已保存的额度开启新周期，历史记录仍可查询。</p>
            <button type="button" class="primary-button" :disabled="busy" @click="save(true)">确认重置</button>
          </div>
          <details v-if="history" :open="personal">
            <summary>变更和重置记录</summary>
            <p v-if="!history.entries.length">暂无记录</p>
            <ul v-else>
              <li v-for="entry in history.entries" :key="entry.id">
                {{ displayTime(entry.createdAt) }} · {{ entry.actor }} ·
                {{ entry.action === "reset" ? "重置周期" : "设置额度" }}{{ displayChange(entry.detail) }}
              </li>
            </ul>
            <footer v-if="page > 1 || history.hasNext" class="permission-pagination">
              <span>第 {{ page }} 页</span>
              <button type="button" class="ghost-button" :disabled="busy || page === 1" @click="run(() => loadHistory(page - 1))">上一页</button>
              <button type="button" class="ghost-button" :disabled="busy || !history.hasNext" @click="run(() => loadHistory(page + 1))">下一页</button>
            </footer>
          </details>
        </template>
      </div>
      <footer v-if="personal" class="modal-actions">
        <button v-if="pool" type="button" class="ghost-button" :disabled="busy" @click="confirmReset = !confirmReset">重置周期</button>
        <button type="button" class="ghost-button" :disabled="busy" @click="closeDialog">关闭</button>
        <button v-if="pool" type="button" class="primary-button" :disabled="busy" @click="save(false)">保存额度</button>
      </footer>
    </component>
  </Teleport>
</template>
