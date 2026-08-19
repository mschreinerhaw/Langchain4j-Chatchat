<template>
  <section class="workspace-panel python-management">
    <header class="panel-heading"><div><h2>Python 管理</h2><p>MCP 统一发布执行环境、接收加密脚本快照，并将模板注册为可执行工具。</p></div><el-button :loading="busy" @click="load">刷新</el-button></header>
    <el-tabs v-model="tab" class="workspace-tabs">
      <el-tab-pane label="执行环境" name="environments">
        <div class="python-toolbar"><el-button type="primary" @click="openEnvironment()">新建环境</el-button></div>
        <el-table :data="environments" border stripe empty-text="尚未配置 Python 环境">
          <el-table-column prop="name" label="环境" min-width="150"/><el-table-column prop="dockerImage" label="Docker 镜像" min-width="210"/><el-table-column prop="pythonVersion" label="Python" width="90"/>
          <el-table-column label="资源" width="150"><template #default="{row}">{{row.cpuLimit}} CPU / {{row.memoryLimit}}</template></el-table-column><el-table-column prop="timeoutSeconds" label="超时(s)" width="90"/><el-table-column label="网络" width="80"><template #default="{row}">{{row.networkEnabled?'开启':'禁用'}}</template></el-table-column>
          <el-table-column label="版本/状态" width="140"><template #default="{row}">v{{row.versionNumber}} · {{row.status}}</template></el-table-column>
          <el-table-column label="操作" width="210" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="row.status!=='DRAFT'" @click="openEnvironment(row)">编辑</el-button><el-button link :type="row.status==='PUBLISHED'?'danger':'success'" @click="toggleEnvironment(row)">{{row.status==='PUBLISHED'?'停用':'发布'}}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="Python 模板" name="templates">
        <el-alert title="源码仅以 AES-GCM 密文落库，执行时才在 MCP 内存中解密；模板由 API 发布后自动出现在此处。" type="info" show-icon :closable="false"/>
        <el-table class="python-template-table" :data="templates" border stripe empty-text="尚未同步 Python 模板">
          <el-table-column prop="templateName" label="模板" min-width="160"/><el-table-column prop="toolName" label="MCP Tool" min-width="210"/><el-table-column prop="scenario" label="场景描述" min-width="240" show-overflow-tooltip/><el-table-column prop="environmentId" label="执行环境" min-width="150"/><el-table-column prop="sourceHash" label="源码 SHA-256" min-width="180" show-overflow-tooltip/><el-table-column prop="status" label="状态" width="110"/>
          <el-table-column label="操作" width="200" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="row.status!=='PUBLISHED'" @click="testTemplate(row)">试运行</el-button><el-button link :type="row.status==='PUBLISHED'?'danger':'success'" @click="toggleTemplate(row)">{{row.status==='PUBLISHED'?'停用':'启用'}}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialogOpen" title="Python 执行环境" width="620px"><el-form label-position="top"><el-form-item label="环境名称 *"><el-input v-model="form.name"/></el-form-item><el-form-item label="描述"><el-input v-model="form.description" type="textarea"/></el-form-item><el-form-item label="Docker 镜像 *"><el-input v-model="form.dockerImage"/></el-form-item><el-row :gutter="12"><el-col :span="8"><el-form-item label="Python"><el-input v-model="form.pythonVersion"/></el-form-item></el-col><el-col :span="8"><el-form-item label="CPU"><el-input v-model="form.cpuLimit"/></el-form-item></el-col><el-col :span="8"><el-form-item label="内存"><el-input v-model="form.memoryLimit"/></el-form-item></el-col></el-row><el-row :gutter="12"><el-col :span="12"><el-form-item label="执行超时（秒）"><el-input-number v-model="form.timeoutSeconds" :min="1" :max="3600"/></el-form-item></el-col><el-col :span="12"><el-form-item label="允许网络"><el-switch v-model="form.networkEnabled"/></el-form-item></el-col></el-row></el-form><template #footer><el-button @click="dialogOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="saveEnvironment">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="resultOpen" title="模板试运行结果" width="720px"><pre class="python-result">{{resultText}}</pre></el-dialog>
  </section>
</template>
<script src="../scripts/views/PythonManagementView.js"></script>
<style src="../styles/views/python-management.css"></style>
