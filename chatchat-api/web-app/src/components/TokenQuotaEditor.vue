<script src="../js/components/TokenQuotaEditor.js"></script>

<template>
  <details @toggle="open">
    <summary>{{ type === "ROLE" ? "角色共享 Token 额度" : "个人 Token 额度" }}</summary>
    <p v-if="busy && !pool">正在加载额度…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="pool">
      <p>周期 {{ pool.period }} · 已消费 {{ pool.spent }} · 已预留 {{ pool.reserved }}</p>
      <p v-if="pool.limit !== null">可用 {{ remaining }} · 已消费比例 {{ percentage }}</p>
      <p v-else>当前额度不限</p>
      <p>上次重置 {{ displayTime(history?.lastResetAt) }}</p>
      <div class="permission-form-grid">
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
      <div class="modal-actions">
        <button type="button" class="ghost-button" :disabled="busy" @click="confirmReset = !confirmReset">重置周期</button>
        <button type="button" class="primary-button" :disabled="busy" @click="save(false)">保存额度</button>
      </div>
      <div v-if="confirmReset">
        <p>按已保存的额度开启新周期，历史记录仍可查询。</p>
        <button type="button" class="primary-button" :disabled="busy" @click="save(true)">确认重置</button>
      </div>
      <details v-if="history">
        <summary>变更和重置记录</summary>
        <p v-if="!history.entries.length">暂无记录</p>
        <ul v-else>
          <li v-for="entry in history.entries" :key="entry.id">
            {{ displayTime(entry.createdAt) }} · {{ entry.actor }} ·
            {{ entry.action === "reset" ? "重置周期" : "设置额度" }}{{ displayChange(entry.detail) }}
          </li>
        </ul>
        <button type="button" class="ghost-button" :disabled="busy || page === 1" @click="run(() => loadHistory(page - 1))">上一页</button>
        <button type="button" class="ghost-button" :disabled="busy || !history.hasNext" @click="run(() => loadHistory(page + 1))">下一页</button>
      </details>
    </template>
  </details>
</template>
