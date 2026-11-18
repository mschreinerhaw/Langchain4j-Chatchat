<template>
  <div class="form-grid">
    <label>
      启动命令 CMD（每行一个参数）
      <textarea v-model="command" />
    </label>
    <label>
      入口命令 ENTRYPOINT（每行一个参数）
      <textarea v-model="entrypoint" />
    </label>
    <label>
      工作目录
      <input v-model="values.workingDir" placeholder="留空继承镜像" />
    </label>
    <label>
      运行用户 UID:GID
      <input v-model="values.user" placeholder="留空继承镜像" pattern="[0-9]+:[0-9]+" />
    </label>
    <label>
      环境变量覆盖（KEY=VALUE）
      <textarea v-model="environment" placeholder="密码或令牌请自行填写；不填则继承镜像" />
    </label>
    <label>
      端口映射（主机端口:容器端口）
      <textarea v-model="ports" />
    </label>
    <label>
      内存上限 (MiB)
      <input v-model.number="values.memoryMiB" type="number" min="16" max="131072" required />
    </label>
    <label>
      CPU 核数上限
      <input v-model.number="values.cpus" type="number" min="0.1" max="64" step="0.1" required />
    </label>
    <label>
      重启策略
      <select v-model="values.restart">
        <option value="unless-stopped">除非手动停止</option>
        <option value="no">不自动重启</option>
        <option value="on-failure">失败时重启</option>
        <option value="always">始终重启</option>
      </select>
    </label>
    <label>
      网络
      <select v-model="values.network">
        <option value="bridge">bridge</option>
        <option value="none">none</option>
      </select>
    </label>
  </div>
</template>
<script setup>
  import { reactive, ref, watch } from 'vue';
  const props = defineProps({ initial: { type: Object, default: () => ({}) } });
  const values = reactive({}),
    command = ref(''),
    entrypoint = ref(''),
    environment = ref(''),
    ports = ref('');
  watch(
    () => props.initial,
    (v) => {
      Object.assign(
        values,
        {
          memoryMiB: 256,
          cpus: 1,
          restart: 'unless-stopped',
          network: 'bridge',
          workingDir: '',
          user: '',
        },
        v
      );
      command.value = (v.command || []).join('\n');
      entrypoint.value = (v.entrypoint || []).join('\n');
      environment.value = Object.entries(v.environment || {})
        .map(([k, value]) => k + '=' + value)
        .join('\n');
      ports.value = (v.ports || []).map((p) => p.host + ':' + p.container).join('\n');
    },
    { immediate: true }
  );
  function read() {
    const env = {};
    for (const line of environment.value.split('\n').filter(Boolean)) {
      const i = line.indexOf('=');
      if (i < 1) throw Error('环境变量必须使用 KEY=VALUE 格式');
      env[line.slice(0, i)] = line.slice(i + 1);
    }
    const mappings = ports.value
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const parts = line.trim().split(':');
        if (parts.length !== 2 || parts.some((p) => !/^\d+$/.test(p)))
          throw Error('端口格式必须为 主机端口:容器端口');
        return { host: Number(parts[0]), container: Number(parts[1]), address: '127.0.0.1' };
      });
    const result = {
      ...values,
      command: command.value ? command.value.split('\n') : [],
      environment: env,
      ports: mappings,
    };
    if (entrypoint.value) result.entrypoint = entrypoint.value.split('\n');
    else delete result.entrypoint;
    if (!result.user) delete result.user;
    if (!result.workingDir) delete result.workingDir;
    return result;
  }
  defineExpose({ read });
</script>
