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
      description="工具名、描述、输入协议、只读策略、风险等级、确认规则、权限模式和输出脱敏边界均不可编辑。此页面只维护固定父级检索工具、角色和模板勾选范围。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-table :data="bindings" border stripe empty-text="暂无模板查询发布绑定">
      <el-table-column prop="toolName" label="工具名称" min-width="230">
        <template #default="{ row }">
          <code>{{ row.toolName }}</code>
          <el-tag size="small" effect="plain">v{{ row.revision || 1 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="父级模板检索" min-width="230">
        <template #default="{ row }">
          <strong>{{ row.parentToolTitle }}</strong>
          <small class="cell-subtitle">{{ row.parentToolName }}</small>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="190">
        <template #default="{ row }">
          <strong>{{ row.roleName || row.roleCode }}</strong>
          <small class="cell-subtitle">{{ row.roleCode }}</small>
        </template>
      </el-table-column>
      <el-table-column label="适用对象" min-width="160">
        <template #default="{ row }">
          {{ row.subjectType === 'USER' ? (row.username || '指定成员') : '角色全部成员' }}
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
        title="动态发布：【业务域】_template_query / read-only / low risk / auto execute"
        description="服务端会执行发布审查、拒绝未绑定范围，并禁止返回命令、SQL、URL、Header、Body 或凭据。"
        type="success"
        :closable="false"
      />
      <el-form label-position="top" class="binding-form">
        <el-form-item label="工具名称" required>
          <el-input v-model.trim="form.domainCode" maxlength="64" placeholder="请输入领域编码，例如 customer_service">
            <template #append>_template_query</template>
          </el-input>
          <div class="field-help">只允许填写领域编码：小写字母、数字和下划线。固定后缀由系统维护，不可修改。</div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="父级模板检索工具" required>
              <el-select v-model="form.parentToolName" class="w-100" placeholder="请选择固定的父级模板检索工具" @change="onParentChange">
                <el-option v-for="item in parents" :key="item.toolName" :value="item.toolName" :label="`${item.title} · ${item.toolName}`" />
              </el-select>
              <div class="field-help">动态工具复用该父级检索逻辑；只能绑定与父级资产类型一致的模板。</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色" required>
              <el-select v-model="form.roleId" class="w-100" filterable placeholder="请选择角色" @change="onRoleChange">
                <el-option v-for="item in roles" :key="item.id" :value="item.id" :label="item.roleName || item.roleCode" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="适用范围" required>
              <el-radio-group v-model="form.subjectType" @change="onSubjectTypeChange">
                <el-radio-button label="ROLE">角色全部成员</el-radio-button>
                <el-radio-button label="USER">指定成员（专人专用）</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="form.subjectType === 'USER'" label="角色组成员" required>
              <el-select v-model="form.userId" class="w-100" filterable placeholder="请选择成员">
                <el-option v-for="item in members" :key="item.id" :value="item.id" :label="item.username" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-alert
          v-if="form.roleId && form.parentToolName"
          :title="`下方仅展示该角色已授权且属于「${selectedParent?.title || ''}」分类的模板`"
          type="info"
          :closable="false"
        />

        <section class="template-picker-toolbar" v-loading="templateLoading">
          <el-select v-model="typeFilter" disabled placeholder="请先选择父级模板检索工具">
            <el-option v-for="(label, value) in typeLabels" :key="value" :value="value" :label="label" />
          </el-select>
          <el-select v-model="businessCategoryFilter" clearable filterable placeholder="全部业务分类">
            <el-option v-for="item in businessCategoryOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <el-input v-model.trim="keyword" clearable placeholder="搜索模板名称、标识、分类或描述" />
          <el-button @click="selectVisible">勾选当前结果</el-button>
          <el-button @click="clearVisible">清除当前结果</el-button>
        </section>

        <div class="selection-summary">
          已选择 <strong>{{ form.templateKeys.length }}</strong> 个模板
          <el-tag v-for="label in selectedTypes" :key="label" size="small" effect="plain">{{ label }}</el-tag>
        </div>

        <el-checkbox-group v-model="form.templateKeys" class="template-groups" v-loading="templateLoading">
          <section v-for="group in groupedTemplates" :key="group.assetType" class="template-group">
            <header><strong>{{ group.label }}</strong><span>{{ group.items.length }} 个可选模板</span></header>
            <div class="template-option-grid">
              <el-checkbox v-for="item in group.items" :key="item.key" :value="item.key" border>
                <span class="template-option-copy">
                  <strong>{{ item.title }}</strong>
                  <code>{{ item.templateId }}</code>
                  <el-tag v-if="item.businessCategoryCode" size="small" effect="plain" class="business-category-tag">
                    {{ item.businessCategoryName || item.businessCategoryCode }}
                  </el-tag>
                  <small>{{ item.description || item.category || '系统维护模板' }}</small>
                </span>
              </el-checkbox>
            </div>
          </section>
          <el-empty
            v-if="!filteredTemplates.length"
            :description="!form.parentToolName ? '请先选择父级模板检索工具' : (form.roleId ? '该角色在当前父级分类下没有已授权模板' : '请再选择角色')"
            :image-size="72"
          />
        </el-checkbox-group>
        <div class="template-pagination">
          <span>共 {{ filteredTemplates.length }} 个模板</span>
          <el-pagination
            v-model:current-page="templatePage"
            :page-size="templatePageSize"
            :total="filteredTemplates.length"
            layout="prev, pager, next"
            background
            hide-on-single-page
          />
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存发布范围</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script src="../scripts/views/TemplateQueryPublicationsView.js"></script>
