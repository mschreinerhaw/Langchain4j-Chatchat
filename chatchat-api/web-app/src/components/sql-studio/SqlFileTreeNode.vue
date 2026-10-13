<template>
  <ul class="sql-file-nodes">
    <li v-for="node in nodes" :key="node.id">
      <details v-if="node.children" open><summary>▾ {{ node.name }}</summary>
        <SqlFileTreeNode :nodes="node.children" :active-id="activeId" :mapping-ids="mappingIds" @open="$emit('open', $event)" @mapping="$emit('mapping', $event)" @context="$emit('context', $event)" />
      </details>
      <div v-else class="sql-tree-file" :class="{ active: activeId === node.id }" @contextmenu.prevent="$emit('context', { file: node.file, event: $event })">
        <input v-if="node.file.kind === 'MAPPING'" type="checkbox" :aria-label="'关联 Mapping ' + node.file.path"
          :checked="mappingIds.includes(node.id)" @change="$emit('mapping', node.id)" />
        <button type="button" :title="node.file.path" @click="$emit('open', node.id)"><small>{{ node.file.kind === 'MAPPING' ? 'MAP' : 'SQL' }}</small>{{ node.name }}<i v-if="node.file.draft !== node.file.content">●</i></button>
      </div>
    </li>
  </ul>
</template>
<script src="../../js/components/sql-studio/SqlFileTreeNode.js"></script>
