<template>
  <section class="feature-view">
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

<script>
export default {
  name: "SystemManagementView",
  data() {
    return {
      settings: [
        { title: "模型服务", description: "配置默认模型、超时和调用额度。" },
        { title: "数据源", description: "管理行情、公告、研报和本地知识库。" },
        { title: "用户权限", description: "按角色控制报告、任务和系统配置入口。" }
      ]
    };
  }
};
</script>
