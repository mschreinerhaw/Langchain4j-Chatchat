<template>
  <nav class="exploration-chain" aria-label="思维链导航">
    <div class="chain-heading"><h2>思维链</h2><div><button type="button" aria-label="新的分析" title="从当前数据开始新的分析" @click="$emit('new-root')"><Plus :size="17" /></button><button type="button" aria-label="折叠思维链" @click="$emit('collapse')"><PanelLeftClose :size="17" /></button></div></div>
    <div class="chain-tree" role="tree" aria-label="分析分支">
      <div v-for="node in rows" :key="node.id" class="chain-item" :class="{'is-selected':node.id===selectedId,'is-child':node.depth>0}" :style="{'--node-depth':Math.min(node.depth,8)}" role="treeitem" :aria-level="node.depth+1" :aria-selected="node.id===selectedId" :aria-expanded="node.hasChildren?node.expanded:undefined">
        <button v-if="node.hasChildren" type="button" class="chain-branch" :aria-label="`${node.expanded?'收起':'展开'}${node.businessTitle}的分支`" @click="$emit('toggle',node.id)"><ChevronDown v-if="node.expanded" :size="13" /><ChevronRight v-else :size="13" /></button><span v-else class="chain-leaf" aria-hidden="true"></span>
        <button type="button" class="chain-select" :data-node-id="node.id" :aria-label="node.businessTitle" @click="$emit('select',node)"><span class="chain-title">{{ node.businessTitle }}</span><span v-if="node.description" class="chain-description">{{ node.description }}</span><small v-if="!node.receipt?.resultAvailable" class="chain-status"><span class="chain-pending" aria-hidden="true"></span>{{ runningId===node.id || (node.receipt?.agentTaskId && !node.receipt?.terminal)?'正在分析':node.status==='RECOVERABLE'?'可继续分析':'等待分析' }}</small></button>
      </div>
      <p v-if="!rows.length" class="chain-empty">从一个问题开始探索，分析过程会保存在这里。</p>
    </div>
  </nav>
</template>
<script src="../js/components/ExplorationChain.js"></script>
<style scoped src="../styles/components/exploration-chain.css"></style>
