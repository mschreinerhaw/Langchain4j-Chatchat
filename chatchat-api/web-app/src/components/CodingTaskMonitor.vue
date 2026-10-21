<template>
  <details class="coding-monitor" @toggle="toggle" data-testid="coding-monitor">
    <summary
      >Python 编程任务 · Cline <span>{{ tasks.length }} 个委派任务</span></summary
    >
    <div v-if="expanded" class="monitor-body">
      <header>
        <strong>编程与执行监控</strong>
        <button
          class="refresh-button"
          type="button"
          title="刷新状态"
          aria-label="刷新状态"
          @click="refresh"
          :disabled="loading"
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M20 7v5h-5M4 17v-5h5" />
            <path d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 17.9 17" />
          </svg>
        </button>
      </header>
      <p v-if="error" role="alert">{{ error }} · 已保留加载的历史记录</p>
      <p v-if="!loading && !tasks.length">该运行暂无 Coding 委派记录。</p>
      <label v-if="tasks.length"
        >任务
        <select v-model="selectedId"
          ><option v-for="task in tasks" :key="task.taskId" :value="task.taskId">{{
            task.taskId
          }}</option></select
        ></label
      >
      <template v-if="selected">
        <h3>{{ selected.objective }}</h3>
        <dl class="task-facts">
          <div
            ><dt>任务编号</dt><dd>{{ selected.taskId }}</dd></div
          >
          <div
            ><dt>尝试编号</dt><dd>{{ selected.attemptId }}</dd></div
          >
          <div
            ><dt>当前阶段</dt><dd aria-live="polite">{{ stageLabels[stage] || stage }}</dd></div
          >
          <div
            ><dt>服务端状态</dt><dd>{{ selected.outcome.status }}</dd></div
          >
          <div
            ><dt>结果校验</dt><dd>{{ selected.outcome.validationStatus || "未记录" }}</dd></div
          >
          <div
            ><dt>创建时间</dt><dd>{{ time(selected.outcome.createdAt) }}</dd></div
          >
          <div
            ><dt>运行时长</dt><dd>{{ elapsed }} 秒</dd></div
          >
          <div
            ><dt>失败 / 停止原因</dt
            ><dd>{{
              selected.outcome.reasonCode || selected.outcome.executionControl?.stopReason || "无"
            }}</dd></div
          >
          <div
            ><dt>代码修正次数</dt><dd>{{ Math.max(0, versions.length - 1) }}</dd></div
          >
        </dl>
        <button v-if="selected.canCancel" @click="cancel" :disabled="cancelling">{{
          cancelling ? "取消请求中" : "取消任务"
        }}</button>
        <button v-if="selected.canResume" @click="resume" :disabled="resuming">{{
          resuming ? "核对中" : "核对并恢复"
        }}</button>
        <p v-if="selected.outcome.summary">{{ selected.outcome.summary }}</p>
        <h4>实际模型调用</h4>
        <p
          >配置快照：{{ selected.configuration.model?.selectedModel || "历史任务未记录" }} ·
          任务内固定</p
        >
        <div class="table-scroll"
          ><table
            ><thead
              ><tr
                ><th>调用</th><th>模型 / 供应商协议</th><th>开始 / 结束</th><th>状态</th
                ><th>耗时 / Tokens</th></tr
              ></thead
            >
            <tbody
              ><tr v-for="call in calls" :key="call.id"
                ><td>{{ call.ordinal }}</td
                ><td
                  >{{ call.providerModel || call.selectedModel || "未记录实际身份" }} /
                  {{ call.protocol || "未记录" }}</td
                ><td>{{ time(call.startedAt) }}<br />{{ time(call.finishedAt) }}</td
                ><td>{{
                  call.state === "RUNNING" && selected.outcome.endedAt
                    ? "未取得完成回执"
                    : call.state
                }}</td
                ><td
                  >{{ call.durationMs ?? "—" }} ms · {{ call.usage?.inputTokens ?? "—" }} /
                  {{ call.usage?.outputTokens ?? "—" }}</td
                ></tr
              ></tbody
            >
          </table></div
        >
        <h4>Skills 与权限</h4>
        <p>已配置：{{ selected.configuration.configuredSkillIds?.join("、") || "无记录" }}</p>
        <p
          >已加载：{{ loadedSkills }} · 方法状态：{{
            selected.configuration.skills?.status || "未加载"
          }}</p
        >
        <p>提示应用：{{ skillApplications }} 次；独立 Skill 工具执行：{{ skillExecutions }} 次</p>
        <p>允许工具：{{ selected.allowedTools.join("、") }}</p>
        <h4>输入快照与数据文件</h4>
        <p v-if="inputError" role="alert">{{ inputError }}</p>
        <p>{{
          inputBinding === "LOADING"
            ? "正在读取所选操作的输入绑定"
            : inputBinding === "OPERATION_FROZEN"
              ? "当前代码操作已冻结的输入版本"
              : "任务当前输入版本；尚未绑定到所选代码操作"
        }}</p>
        <article v-for="input in inputVersions" :key="input.reference">
          <p>{{ input.reference }} · {{ input.format }} · {{ input.rowCount }} 行</p>
          <p>清单 SHA-256：{{ input.sha256 }}</p>
          <div class="table-scroll"
            ><table
              ><thead
                ><tr><th>文件</th><th>文件 ID</th><th>行数 / 字节</th><th>SHA-256</th></tr></thead
              >
              <tbody
                ><tr
                  v-for="file in input.chunks?.length ? input.chunks : [input]"
                  :key="file.fileId"
                  ><td>{{ file.fileName }}</td
                  ><td>{{ file.fileId }}</td
                  ><td>{{ file.rowCount }} / {{ file.size }}</td
                  ><td>{{ file.sha256 }}</td></tr
                ></tbody
              >
            </table></div
          >
          <details
            ><summary>字段类型与数据质量</summary
            ><pre>{{
              JSON.stringify(
                {
                  schema: input.inputManifest?.columns || input.schemaJson,
                  quality: input.qualitySummary,
                },
                null,
                2
              )
            }}</pre>
          </details>
        </article>
        <h4>代码工作区 · main.py</h4>
        <p v-if="!versions.length"
          >尚无完整代码版本。模型请求期间显示 CODING，返回完整工具参数后显示代码。</p
        >
        <label v-if="versions.length"
          >版本
          <select v-model="selectedOperation"
            ><option
              v-for="(version, index) in versions"
              :key="version.operationId"
              :value="version.operationId"
              >v{{ index + 1 }} · {{ version.state }} · {{ time(version.generatedAt) }}</option
            ></select
          ></label
        >
        <p v-if="version"
          >SHA-256：{{ version.hash }} · {{ version.state }} · 校验
          {{ version.validationStatus || "待回执" }} {{ version.outputStatus || "" }}</p
        >
        <p v-if="codeError" role="alert"
          >{{ codeError }} <button @click="loadCode">重读源码</button></p
        >
        <nav v-if="code"
          ><button @click="showDiff = false">完整源码</button
          ><button @click="loadDiff" :disabled="!previousVersion">与上一版本对比</button></nav
        >
        <p v-if="code?.redacted">源码中的凭据已脱敏；展示文本不用于执行或哈希计算。</p>
        <PythonCodeDiff
          v-if="showDiff && code"
          :diff="diff"
          action="fix"
          request="与上一份提交源码对比"
          scope="Runtime 受控执行版本"
        />
        <pre v-else-if="code" tabindex="0"><code>{{ code.content }}</code></pre>
        <h4>持久化事件时间线 · {{ selectedEvents.length }} 条</h4>
        <ol class="event-list"
          ><li v-for="event in visibleEvents" :key="event.eventId"
            ><time>{{ time(event.occurredAt) }}</time>
            <strong>#{{ event.sequence }} {{ event.payload?.type || event.type }}</strong
            ><span>{{ event.payload?.stage || event.status }} · {{ event.summary }}</span
            ><small
              >{{ event.payload?.source || "历史来源未记录" }} ·
              {{ event.payload?.attemptId || "历史尝试未记录" }}</small
            ><small>{{
              event.operationId || event.payload?.proposedOperationId || event.eventId
            }}</small
            ><span v-if="event.payload?.tool"
              >工具：{{ event.payload.tool }} · {{ event.payload.status || "状态见后续事件" }}</span
            ><details v-if="event.payload" class="event-payload"
              ><summary>查看事件详情</summary
              ><pre>{{ JSON.stringify(event.payload, null, 2) }}</pre>
            </details></li
          ></ol
        >
        <button v-if="eventLimit < selectedEvents.length" @click="eventLimit += 100"
          >展开更多历史事件</button
        >
      </template>
    </div>
  </details>
</template>

<script lang="ts" src="../js/components/CodingTaskMonitor.ts"></script>

<style scoped src="../styles/components/coding-task-monitor.css"></style>
