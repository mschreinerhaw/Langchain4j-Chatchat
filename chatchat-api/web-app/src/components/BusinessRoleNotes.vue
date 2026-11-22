<template>
  <div class="business-role-notes">
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="loadFailed" type="button" :disabled="busy" @click="load">重新加载角色说明</button>
    <button v-if="page.canPublish && !editing" type="button" @click="editing = true">发布角色说明</button>
    <form v-if="editing" class="business-card business-note-form" @submit.prevent="save">
      <p v-if="!page.roles.length">当前租户暂无可选角色。</p>
      <label>
        发布给角色
        <select v-model="roleId" :disabled="busy" required @change="selectRole">
          <option value="" disabled>请选择角色</option>
          <option v-for="role in page.roles" :key="role.id" :value="role.id">{{ role.name }} · {{ role.code }}</option>
        </select>
      </label>
      <template v-if="roleId">
        <label>说明标题<input v-model="title" :disabled="busy" required maxlength="120" /></label>
        <label>说明内容<textarea v-model="body" :disabled="busy" required maxlength="4000" rows="5"></textarea></label>
      </template>
      <div class="business-actions">
        <button type="submit" :disabled="busy || !roleId || !title.trim() || !body.trim()">
          {{ published ? "更新说明" : "发布说明" }}
        </button>
        <button v-if="published" type="button" :disabled="busy" @click="withdraw">撤回说明</button>
        <button type="button" :disabled="busy" @click="closeEditor">关闭</button>
      </div>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <article v-for="note in page.notes" :key="note.roleId" class="business-card">
      <small>{{ note.roleName }} · 角色说明</small>
      <h2>{{ note.title }}</h2>
      <p class="business-note-body">{{ note.body }}</p>
    </article>
    <AppConfirmDialog :request="confirmation" @resolve="settleConfirmation" />
  </div>
</template>

<script src="../js/components/BusinessRoleNotes.js"></script>
