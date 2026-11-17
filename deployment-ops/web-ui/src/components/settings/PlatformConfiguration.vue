<template>
  <div class="grid-two">
    <PanelCard title="平台配置">
      <ResourceState :error="error" @retry="refresh" />
      <form @submit.prevent="save">
        <label>
          组织名称
          <input v-model="organization" maxlength="1024" />
        </label>
        <p v-if="message" class="notice success">{{ message }}</p>
        <p v-if="saveError" class="notice danger" role="alert">{{ saveError }}</p>
        <button class="primary">保存配置</button>
      </form>
    </PanelCard>
    <PanelCard title="应用入口管理">
      <form @submit.prevent="saveEntry">
        <label>
          入口名称
          <input v-model="entry.name" required />
        </label>
        <label>
          访问地址
          <input v-model="entry.url" type="url" required />
        </label>
        <label>
          所属应用
          <input v-model="entry.project" />
        </label>
        <button>添加入口</button>
      </form>
      <DataTable :rows="entries" :columns="columns">
        <template #url="{ row }">
          <a :href="row.url" target="_blank" rel="noopener">{{ row.url }}</a>
        </template>
      </DataTable>
    </PanelCard>
  </div>
</template>
<script setup>
  import { ref, reactive, watch } from 'vue';
  import { platformApi } from '../../api/platform';
  import { adminApi } from '../../api/admin';
  import { useResource } from '../../composables/useResource';
  import PanelCard from '../ui/PanelCard.vue';
  import DataTable from '../ui/DataTable.vue';
  import ResourceState from '../ui/ResourceState.vue';
  const { data, error, refresh } = useResource(platformApi.configuration, { initial: [] }),
    { data: entries, refresh: refreshEntries } = useResource(adminApi.entries, { initial: [] }),
    organization = ref(''),
    message = ref(''),
    saveError = ref(''),
    entry = reactive({ name: '', url: '', project: '' });
  watch(
    data,
    (values) =>
      (organization.value =
        values.find((v) => v.setting_key === 'organization.name')?.setting_value || '')
  );
  const columns = [
    { key: 'name', label: '入口' },
    { key: 'url', label: '地址' },
  ];
  async function save() {
    try {
      await platformApi.saveConfiguration({ key: 'organization.name', value: organization.value });
      message.value = '平台配置已持久化。';
      await refresh();
    } catch (e) {
      saveError.value = e.message;
    }
  }
  async function saveEntry() {
    try {
      await adminApi.saveEntry({ ...entry });
      await refreshEntries();
    } catch (e) {
      saveError.value = e.message;
    }
  }
</script>
