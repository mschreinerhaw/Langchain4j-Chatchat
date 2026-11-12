<template>
  <nav class="exploration-chain" aria-label="思维链导航">
    <div class="chain-heading"><h2>分析思维链</h2><div><button type="button" aria-label="新的分析" title="从当前数据开始新的分析" @click="$emit('new-root')"><Plus :size="17" /></button><button type="button" aria-label="折叠思维链" @click="$emit('collapse')"><PanelLeftClose :size="17" /></button></div></div>
    <div class="chain-tree" role="tree" aria-label="分析分支">
      <div v-for="node in rows" :key="node.id" class="chain-item" :class="{'is-selected':node.id===selectedId,'is-child':node.depth>0}" :style="{'--node-depth':Math.min(node.depth,8)}" role="treeitem" :aria-level="node.depth+1" :aria-selected="node.id===selectedId" :aria-expanded="node.hasChildren?node.expanded:undefined">
        <button v-if="node.hasChildren" type="button" class="chain-branch" :aria-label="`${node.expanded?'收起':'展开'}${node.businessTitle}的分支`" @click="$emit('toggle',node.id)"><ChevronDown v-if="node.expanded" :size="13" /><ChevronRight v-else :size="13" /></button><span v-else class="chain-leaf" aria-hidden="true"></span>
        <button type="button" class="chain-select" :data-node-id="node.id" :data-state="node.state.code" :aria-label="node.businessTitle" @click="$emit('select',node)">
          <span class="chain-title"><LoaderCircle v-if="['RUNNING','STOPPING'].includes(node.state.code)" :size="13" class="chain-spinner" /><CircleCheck v-else-if="node.state.code==='COMPLETED'" :size="13" class="chain-completed" /><CircleAlert v-else-if="node.state.code==='FAILED'" :size="13" class="chain-failed" /><CircleMinus v-else-if="node.state.code==='CANCELLED'" :size="13" /><span v-else class="chain-pending" />{{ node.businessTitle }}</span>
          <span v-if="node.description" class="chain-description">{{ node.description }}</span>
          <small v-if="node.receipt?.visualReport" class="chain-artifacts"><ChartColumn :size="12" /><template v-if="node.receipt.visualReport.htmlReport">交互式报告</template><template v-else>{{ node.receipt.visualReport.charts?.length || 0 }} 图表 · {{ node.receipt.visualReport.tables?.length || 0 }} 数据表</template></small>
          <small class="chain-status">{{ node.id===selectedId ? '当前选中节点 · ' : '' }}{{ node.state.label }}</small>
        </button>
      </div>
      <p v-if="!rows.length" class="chain-empty">从一个问题开始探索，分析过程会保存在这里。</p>
    </div>
  </nav>
</template>
<script src="../js/components/ExplorationChain.js"></script>
<style scoped src="../styles/components/exploration-chain.css"></style>
