<template>
  <main class="access-denied-view">
    <section>
      <strong>暂无访问权限</strong>
      <p>当前角色未授权访问此功能，请联系管理员调整菜单权限。</p>
    </section>
  </main>
</template>

<style scoped>
.access-denied-view { min-height: 100%; display: grid; place-items: center; padding: 32px; background: #f6f8fb; }
.access-denied-view section { max-width: 480px; padding: 28px; border: 1px solid #dce4f1; border-radius: 12px; background: #fff; text-align: center; box-shadow: 0 10px 28px rgb(16 24 40 / 7%); }
.access-denied-view strong { color: #202939; font-size: 20px; }
.access-denied-view p { margin: 10px 0 0; color: #667085; line-height: 1.7; }
</style>
