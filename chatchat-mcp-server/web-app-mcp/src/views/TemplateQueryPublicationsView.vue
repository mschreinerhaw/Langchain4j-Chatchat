<template>
  <el-card class="workspace-panel template-publication-page" shadow="never" v-loading="loading">
    <template #header>
      <div class="panel-heading">
        <div>
          <h2>template_query 动态发布</h2>
          <p>按 MCP 服务和角色维护可查询模板范围；运行时只返回已勾选且仍处于启用状态的模板。</p>
        </div>
        <div class="panel-actions">
          <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
          <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增绑定</el-button>
        </div>
      </div>
    </template>

    <el-alert
      title="工具治理规范由系统固定维护"
      description="工具名、描述、输入协议、只读策略、风险等级、确认规则、权限模式和输出脱敏边界均不可编辑。此页面只维护服务、角色和模板勾选范围。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-table :data="bindings" border stripe empty-text="暂无模板查询发布绑定">
      <el-table-column prop="serviceName" label="MCP 服务" min-width="180" />
      <el-table-column label="角色" min-width="190">
        <template #default="{ row }">
          <strong>{{ row.roleName || row.roleCode }}</strong>
          <small class="cell-subtitle">{{ row.roleCode }}</small>
        </template>
      </el-table-column>
      <el-table-column prop="tenantId" label="租户" min-width="130" />
      <el-table-column label="模板范围" min-width="330">
        <template #default="{ row }">
          <div class="binding-scope-tags">
            <el-tag v-for="label in bindingTypeLabels(row)" :key="label" size="small" effect="plain">{{ label }}</el-tag>
            <span>{{ (row.templateKeys || []).length }} 个模板</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="210">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑范围</el-button>
          <el-button link type="warning" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="form.id ? '编辑 template_query 发布范围' : '新增 template_query 发布范围'" width="980px" destroy-on-close>
      <el-alert
        title="固定发布：template_query / read-only / low risk / auto execute"
        description="服务端会执行发布审查、拒绝未绑定范围，并禁止返回命令、SQL、URL、Header、Body 或凭据。"
        type="success"
        :closable="false"
      />
      <el-form label-position="top" class="binding-form">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="MCP 服务" required>
              <el-select v-model="form.serviceId" class="w-100" filterable placeholder="请选择已启用的 MCP 服务">
                <el-option v-for="item in services" :key="item.id" :value="item.id" :label="`${item.name} · ${item.environment}`" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色" required>
              <el-select v-model="form.roleId" class="w-100" filterable placeholder="请选择已同步角色">
                <el-option v-for="item in roles" :key="item.id" :value="item.id" :label="`${item.roleName || item.roleCode} · ${item.tenantId}`" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <section class="template-picker-toolbar">
          <el-select v-model="typeFilter" clearable placeholder="全部模板类型">
            <el-option v-for="(label, value) in typeLabels" :key="value" :value="value" :label="label" />
          </el-select>
          <el-input v-model.trim="keyword" clearable placeholder="搜索模板名称、标识、分类或描述" />
          <el-button @click="selectVisible">勾选当前结果</el-button>
          <el-button @click="clearVisible">清除当前结果</el-button>
        </section>

        <div class="selection-summary">
          已选择 <strong>{{ form.templateKeys.length }}</strong> 个模板
          <el-tag v-for="label in selectedTypes" :key="label" size="small" effect="plain">{{ label }}</el-tag>
        </div>

        <el-checkbox-group v-model="form.templateKeys" class="template-groups">
          <section v-for="group in groupedTemplates" :key="group.assetType" class="template-group">
            <header><strong>{{ group.label }}</strong><span>{{ group.items.length }} 个可选模板</span></header>
            <div class="template-option-grid">
              <el-checkbox v-for="item in group.items" :key="item.key" :value="item.key" border>
                <span class="template-option-copy">
                  <strong>{{ item.title }}</strong>
                  <code>{{ item.templateId }}</code>
                  <small>{{ item.description || item.category || '系统维护模板' }}</small>
                </span>
              </el-checkbox>
            </div>
          </section>
        </el-checkbox-group>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存发布范围</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script src="../scripts/views/TemplateQueryPublicationsView.js"></script>
