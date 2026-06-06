<template>
  <section class="info-card" :class="{ collapsed }">
    <header>
      <button
        class="info-card-title"
        type="button"
        :disabled="!collapsible"
        :aria-expanded="collapsible ? !collapsed : undefined"
        @click="collapsible && toggleCollapsed()"
      >
        <span class="info-card-heading">{{ title }}</span>
      </button>
      <div class="info-card-actions">
        <button v-if="action" class="info-card-link" type="button" @click="$emit('action')">
          {{ action }} <span></span>
        </button>
        <button
          v-if="collapsible"
          class="info-card-collapse"
          type="button"
          :aria-label="collapsed ? '展开' : '收起'"
          :title="collapsed ? '展开' : '收起'"
          @click="toggleCollapsed"
        >
          <span></span>
        </button>
      </div>
    </header>
    <div v-show="!collapsed" class="info-card-body">
      <slot />
    </div>
  </section>
</template>

<script src="../js/components/InfoCard.js"></script>
