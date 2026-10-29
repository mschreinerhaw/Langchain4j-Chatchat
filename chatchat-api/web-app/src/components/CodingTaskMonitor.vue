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
        <h3 v-if="selected.objective.length <= 240">{{ selected.objective }}</h3>
        <details v-else class="task-objective"><summary>查看完整任务目标与约束</summary><p>{{ selected.objective }}</p></details>
        <dl class="task-facts">
          <div><dt>任务编号</dt><dd>{{ selected.taskId }}</dd></div>
          <div><dt>尝试编号</dt><dd>{{ selected.attemptId }}</dd></div>
          <div><dt>当前阶段</dt><dd aria-live="polite">{{ stageLabels[stage] || stage }}</dd></div>
          <div><dt>服务端状态</dt><dd>{{ selected.outcome.status }}</dd></div>
          <div><dt>结果校验</dt><dd>{{ selected.outcome.validationStatus || '未记录' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ time(selected.outcome.createdAt) }}</dd></div>
          <div><dt>运行时长</dt><dd>{{ elapsed }} 秒</dd></div>
          <div><dt>失败 / 停止原因</dt><dd>{{ selected.outcome.reasonCode || selected.outcome.executionControl?.stopReason || '无' }}</dd></div>
          <div><dt>代码版本变化</dt><dd>{{ Math.max(0, versions.length - 1) }}</dd></div>
        </dl>
        <button v-if="selected.canCancel" @click="cancel" :disabled="cancelling">{{
          cancelling ? "取消请求中" : "取消任务"
        }}</button>
        <button v-if="selected.canResume" @click="resume" :disabled="resuming">{{
          resuming ? "核对中" : "核对并恢复"
        }}</button>
        <p v-if="selected.outcome.summary">{{ selected.outcome.summary }}</p>
        <h4>Python 执行与修复反馈</h4>
        <dl class="task-facts python-overview" data-testid="python-overview">
          <div><dt>Python 执行次数</dt><dd>{{ diagnostics.total }}</dd></div>
          <div><dt>进程成功次数</dt><dd>{{ diagnostics.processSucceeded }}</dd></div>
          <div><dt>进程失败 / 超时次数</dt><dd>{{ diagnostics.processFailed }}</dd></div>
          <div><dt>产物校验失败次数</dt><dd>{{ diagnostics.outputFailed }}</dd></div>
          <div><dt>自动修复次数</dt><dd>{{ executions.length ? diagnostics.repairs : '历史任务未记录' }}</dd></div>
          <div><dt>修复后仍失败次数</dt><dd>{{ diagnostics.repairFailed }}</dd></div>
          <div><dt>已取消执行</dt><dd>{{ diagnostics.cancelled }}</dd></div>
          <div><dt>当前代码版本</dt><dd>{{ diagnostics.latestVersion ? 'v' + diagnostics.latestVersion : versions.at(-1)?.scriptVersion ? 'v' + versions.at(-1)?.scriptVersion : '未记录' }}</dd></div>
          <div><dt>最后一次错误分类（保留历史）</dt><dd>{{ diagnostics.lastError?.category || '未记录错误' }} · {{ diagnostics.lastError?.diagnosticCode || '—' }}</dd></div>
          <div><dt>最后一次产物验证</dt><dd>{{ diagnostics.lastValidation || '尚无验证回执' }}</dd></div>
        </dl>
        <p>进程退出成功不等于产物有效，也不等于业务结论已验证。模型调用成功不会计入 Python 执行次数。</p>
        <p>执行次数按已派发的执行尝试去重；等待执行器回执的请求不计为进程成功或失败。</p>
        <p v-if="!executions.length">历史任务未记录 Python 执行反馈或调度证据；不会根据模型事件补造执行记录。</p>
        <details v-for="execution in executions" :key="execution.executionId" class="python-execution"
          :data-execution-id="execution.executionId" :open="!!execution.feedback?.error">
          <summary>第 {{ execution.ordinal }} 次执行 · {{ execution.script ? 'v' + execution.script.scriptVersion : '版本未记录' }} · {{ stageLabels[execution.state] || execution.state }}
            <strong v-if="execution.feedback?.error"> · {{ execution.feedback.error.exceptionType || execution.feedback.error.diagnosticCode }}</strong>
          </summary>
          <dl class="task-facts">
            <div><dt>执行 ID</dt><dd>{{ execution.executionId }}</dd></div>
            <div><dt>开始时间（执行器回执）</dt><dd data-testid="execution-started-at"><time v-if="timings[execution.executionId]?.startedAt" :datetime="timings[execution.executionId].startedAt || undefined">{{ time(timings[execution.executionId].startedAt || undefined) }}</time><span v-else>—</span></dd></div>
            <div><dt>结束时间（执行器回执）</dt><dd data-testid="execution-finished-at"><time v-if="timings[execution.executionId]?.finishedAt" :datetime="timings[execution.executionId].finishedAt || undefined">{{ time(timings[execution.executionId].finishedAt || undefined) }}</time><span v-else>—</span></dd></div>
            <div><dt>Java 执行请求时间</dt><dd>{{ time(execution.requestedAt) }}</dd></div>
            <div><dt>反馈记录时间</dt><dd>{{ time(execution.feedbackRecordedAt) }}</dd></div>
            <div><dt>耗时 / 退出码</dt><dd>{{ execution.feedback?.durationMs ?? '未测得' }} ms / {{ execution.feedback?.exitCode ?? '未知' }}</dd></div>
            <div><dt>Python 文件</dt><dd>{{ execution.script?.fileName || '历史任务未记录' }}</dd></div>
            <div><dt>本次代码 SHA-256</dt><dd>{{ execution.codeHash || '未记录' }}</dd></div>
            <div><dt>异常类型</dt><dd>{{ execution.feedback?.error?.exceptionType || '未记录异常类型' }}</dd></div>
            <div><dt>错误分类 / 诊断错误码</dt><dd>{{ execution.feedback?.error?.category || '无已记录错误' }} / {{ execution.feedback?.error?.diagnosticCode || '—' }}</dd></div>
            <div><dt>进程状态</dt><dd>{{ execution.feedback?.status || '等待真实停止回执' }}</dd></div>
            <div><dt>产物状态 / 验证</dt><dd>{{ execution.feedback?.outputStatus || (execution.feedback?.resultAvailable ? '已归档' : '无已验证产物') }} / {{ execution.feedback?.validationStatus || '未记录' }}</dd></div>
            <div><dt>业务校验</dt><dd>{{ execution.feedback?.businessValidationPassed === true ? '回执记录通过' : '未独立验证通过' }}</dd></div>
          </dl>
          <p v-if="timingErrors[execution.executionId]">{{ timingErrors[execution.executionId] }}</p>
          <p v-if="execution.feedback?.error" class="diagnostic-message">安全错误消息：{{ execution.feedback.error.message }}</p>
          <p v-if="execution.feedback?.error">此处为执行器诊断及系统修复建议，不是模型编造的根因。</p>
          <ol v-if="execution.feedback?.error?.stack?.length" class="safe-stack">
            <li v-for="(frame, index) in execution.feedback.error.stack" :key="index"><code>{{ frame.file }}:{{ frame.line }}{{ frame.column ? ':' + frame.column : '' }}</code></li>
          </ol>
          <p v-else>安全定位帧未记录，无法还原完整 Traceback。</p>
          <p>原始日志不可用 · 仅保留安全诊断元数据；stdout {{ execution.feedback?.stdoutBytes ?? '未记录' }} 字节，stderr {{ execution.feedback?.stderrBytes ?? '未记录' }} 字节。</p>
          <p v-if="execution.feedback?.repair">修复策略：{{ execution.feedback.repair.action }} · {{ execution.feedback.repair.reason }} · 已用 {{ execution.feedback.repair.attemptsUsed }} / {{ execution.feedback.repair.maxAttempts }}</p>
          <p v-if="execution.feedback?.error?.exceptionType === 'ModuleNotFoundError'">依赖名未从异常原文保存；请查看对应版本错误行的真实导入语句，不据固定诊断消息推测包名。</p>
          <button type="button" @click="selectExecution(execution.executionId)">查看此版本源码与定位</button>
        </details>
        <h4>自动修复关系</h4>
        <p v-if="!repairs.length">尚无可核对的修复关联。相邻代码版本不自动视为修复；旧记录缺少关联证据时保持未知。</p>
        <article v-for="repair in repairs" :key="repair.to.executionId" class="python-repair" :data-repair-execution="repair.to.executionId">
          <strong>失败执行 {{ repair.from.ordinal }}（{{ repair.from.script ? 'v' + repair.from.script.scriptVersion : '版本未记录' }}） → 后续执行 {{ repair.to.ordinal }}（{{ repair.to.script ? 'v' + repair.to.script.scriptVersion : '版本未记录' }}）</strong>
          <p>{{ repair.from.executionId }} → {{ repair.to.executionId }}</p>
          <p>{{ repair.status }} · 后续产物校验 {{ repair.to.feedback?.validationStatus || '待回执' }} · 业务校验 {{ repair.to.feedback?.businessValidationPassed === true ? '回执通过' : '未独立验证通过' }}</p>
          <button type="button" @click="selectRepair(repair)">查看修复 Diff</button>
        </article>
        <h4>Python 执行时间线</h4>
        <ol class="event-list python-timeline" data-testid="python-timeline">
          <li v-for="event in executionTimeline" :key="event.id"><time>{{ time(event.occurredAt) }}</time><strong>#{{ event.sequence }} {{ event.label }}</strong><small>{{ event.executionId }}</small></li>
        </ol>
        <p v-if="!executionTimeline.length">历史事件时间未记录；已归档执行反馈仍在上方展示。</p>
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
        <h4 ref="codePanel">代码工作区 · {{ version?.fileName || code?.fileName || '待提交' }}</h4>
        <p v-if="!versions.length">尚无完整代码版本。模型请求期间显示 CODING，返回完整工具参数后显示代码。</p>
        <label v-if="versions.length">版本 <select v-model="selectedOperation"><option v-for="(version, index) in versions" :key="version.operationId" :value="version.operationId">v{{ version.scriptVersion ?? index + 1 }} · {{ version.fileName }} · {{ stageLabels[version.state] || version.state }} · {{ time(version.generatedAt) }}</option></select></label>
        <p v-if="version">SHA-256：{{ version.hash }} · {{ stageLabels[version.state] || version.state }} · 校验 {{ version.validationStatus || '待回执' }} {{ version.outputStatus || '' }}</p>
        <p v-if="codeError" role="alert">{{ codeError }} <button @click="loadCode">重读源码</button></p>
        <nav v-if="code"><button @click="showDiff = false">完整源码</button><button @click="loadDiff" :disabled="!previousVersion">与上一版本对比</button></nav>
        <p v-if="code?.redacted">源码中的凭据已脱敏；展示文本不用于执行或哈希计算。</p>
        <p v-if="focusedExecution?.feedback?.error?.stack?.length">当前执行错误定位：<span v-for="(frame,index) in focusedExecution.feedback.error.stack" :key="index"> {{ frame.file }}:{{ frame.line }} </span>；以上定位仅对应当前执行版本。</p>
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
