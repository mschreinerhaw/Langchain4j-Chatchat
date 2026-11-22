<template>
  <div class="business-reference-picker" role="region" aria-label="添加技能或文档">
    <div class="business-reference-tabs" role="group" aria-label="资源类型">
      <button v-for="item in types" :key="item.id" type="button"
        :aria-pressed="type === item.id" @click="selectType(item.id)">{{ item.label }}</button>
    </div>
    <p v-if="busy" role="status">正在查询授权资源…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!items.length" role="status">没有可添加的资源。</p>
    <ul v-else aria-label="候选资源">
      <li v-for="(item, index) in items" :key="item.id">
        <button type="button" :class="{ active: index === cursor }" @mouseenter="cursor = index"
          @click="choose(item)"><strong>{{ item.name }}</strong><small>{{ item.category || '' }}</small></button>
      </li>
    </ul>
    <div v-if="pages > 1" class="business-reference-tabs">
      <button type="button" :disabled="busy || page === 0" @click="load(page - 1)">上一页</button>
      <small>{{ page + 1 }} / {{ pages }}</small>
      <button type="button" :disabled="busy || page + 1 >= pages" @click="load(page + 1)">下一页</button>
    </div>
  </div>
</template>
<script src="../js/components/BusinessReferencePicker.js"></script>
