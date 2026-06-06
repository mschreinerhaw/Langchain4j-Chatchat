<template>
  <section class="feature-view system-management-view">
    <header>
      <p>系统管理</p>
      <h1>权限、模型与数据源配置</h1>
    </header>
    <div class="settings-list">
      <article v-for="setting in settings" :key="setting.title">
        <div>
          <strong>{{ setting.title }}</strong>
          <span>{{ setting.description }}</span>
        </div>
        <button type="button">配置</button>
      </article>
    </div>
  </section>
</template>

<script src="../js/views/SystemManagementView.js"></script>
