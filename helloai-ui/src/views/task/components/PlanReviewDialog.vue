<template>
  <el-dialog
    v-model="visible"
    class="plan-review-dialog"
    width="min(1120px, 94vw)"
    top="6vh"
    append-to-body
    :show-close="false"
    @open="loadDrafts"
    @close="$emit('close')"
  >
    <!-- 头部：图标 + 标题 + 状态徽标 + 副标题（原型：弹窗头部 1072×98，padding 28/32/20/32） -->
    <template #header>
      <div class="prd-head">
        <div class="prd-head-main">
          <div class="prd-head-icon">
            <el-icon><Document /></el-icon>
          </div>
          <div class="prd-head-text">
            <div class="prd-head-title-row">
              <h3 class="prd-head-title">
                拆解草案审阅
              </h3>
              <span
                v-if="task"
                class="prd-badge"
                :class="`is-${task.status === 'PLANNING' ? 'progress' : 'waiting'}`"
              >{{ TASK_STATUS_MAP[task.status]?.label || task.status }}</span>
            </div>
            <p class="prd-head-sub">
              任务「{{ task?.title }}」的 AI 拆解草案，确认后草案转正为待分配子任务并按配置自动分发
            </p>
          </div>
        </div>
        <button
          type="button"
          class="prd-close"
          aria-label="关闭"
          @click="visible = false"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </template>

    <!-- 不要用 v-loading 盖住正文：Element Plus 的遮罩是 rgba(255,255,255,.9)（暗色下为黑色遮罩），
         会把说明条与「AI 正在后台拆解」提示一起冲淡，两种配色下都读不了。
         改为正文常亮 + 独立的等待块（见 .prd-wait），文字始终保对比度。 -->
    <div class="prd-body">
      <!-- 说明条：浅紫底圆角 12（原型 #F7F5FF / #EADDFF） -->
      <div class="prd-note">
        <el-icon class="prd-note-icon">
          <InfoFilled />
        </el-icon>
        <span>AI 按任务目标拆出子任务草案。逐条核对标题、验收标准与依赖后确认分发；确认前可修订字段、细拆或重生成。</span>
      </div>

      <!-- 生成中：动态过程卡片（参考原型 4 态 22%/46%/68%/88%）——
           解析任务目标 → 拆解子任务 → 校验依赖关系 → 生成草案字段。
           文案轮播/步骤/进度由真实信号驱动：已用时（真实计时）、已拆出草案数（真实轮询值）、
           步骤与百分比是按信号推算的展示态，不代表后端已上报阶段进度。 -->
      <div
        v-if="generating"
        class="prd-proc"
      >
        <div class="prd-proc-head">
          <div class="prd-proc-title">
            <span class="prd-proc-title-dot" />
            <span class="prd-proc-title-text">AI 后台拆解中</span>
          </div>
          <span class="prd-proc-badge">
            <span class="prd-proc-badge-dot" />
            第 {{ stepIndex + 1 }} / {{ PROC_STEPS.length }} 步 · {{ PROC_STEPS[stepIndex].label }}
          </span>
        </div>

        <div class="prd-proc-main">
          <!-- 加载圆环：沿用原型的半环 + 中心点，做匀速旋转（prefers-reduced-motion 关闭） -->
          <div class="prd-ring">
            <svg viewBox="0 0 64 64" aria-hidden="true">
              <circle class="prd-ring-track" cx="32" cy="32" r="25" fill="none" stroke-width="5" />
              <path
                class="prd-ring-arc"
                d="M 57 25 A 25 25 0 0 0 32 7"
                fill="none"
                stroke-width="5"
                stroke-linecap="round"
              />
              <circle class="prd-ring-disc" cx="32" cy="32" r="11" />
              <circle class="prd-ring-core" cx="32" cy="32" r="4.5" />
            </svg>
          </div>

          <div class="prd-carousel">
            <div class="prd-carousel-window">
              <div class="prd-carousel-line is-prev">
                <span class="prd-carousel-bullet" />
                <span class="prd-carousel-msg is-sm">{{ carouselMsgs.prev }}</span>
              </div>
              <div class="prd-carousel-current">
                <span class="prd-carousel-bar" />
                <span class="prd-carousel-msg is-cur">{{ carouselMsgs.cur }}</span>
              </div>
              <div class="prd-carousel-line is-next">
                <span class="prd-carousel-bullet" />
                <span class="prd-carousel-msg is-sm">{{ carouselMsgs.next }}</span>
              </div>
            </div>
            <div class="prd-carousel-foot">
              <div class="prd-carousel-dots">
                <span
                  v-for="(_, i) in PROC_MSGS"
                  :key="i"
                  class="prd-carousel-dot"
                  :class="{ 'is-cur': i === carouselIdx }"
                />
              </div>
              <span class="prd-carousel-hint"> </span>
            </div>
          </div>
        </div>

        <div class="prd-proc-progress">
          <div class="prd-proc-progress-head">
            <span>草案生成进度</span>
            <b>{{ PROC_STEPS[stepIndex].pct }}%</b>
          </div>
          <div class="prd-proc-track">
            <div
              class="prd-proc-track-fill"
              :style="{ width: PROC_STEPS[stepIndex].pct + '%' }"
            />
          </div>
        </div>

        <div class="prd-proc-steps">
          <template
            v-for="(s, i) in PROC_STEPS"
            :key="s.label"
          >
            <div
              v-if="i > 0"
              class="prd-proc-connector"
              :class="{ 'is-done': i <= stepIndex }"
            />
            <div
              class="prd-proc-step"
              :class="i === stepIndex ? 'is-cur' : (i < stepIndex ? 'is-done' : 'is-pending')"
            >
              <span class="prd-proc-step-dot">
                <svg
                  v-if="i < stepIndex"
                  viewBox="0 0 10 8"
                  class="prd-proc-step-check"
                  aria-hidden="true"
                >
                  <path
                    d="M1 4 L4 7 L9 1"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <span class="prd-proc-step-label">{{ s.label }}</span>
            </div>
          </template>
        </div>
      </div>
      <!-- 拆解产出不再因质量问题被整批丢弃：草案照常可见，问题落在每条草案的自检明细里，
           确认时才作为阻塞点拒绝。这里给出总量提示，避免用户点确认才发现。 -->
      <el-alert
        v-if="gateBlockTotal > 0"
        type="error"
        :closable="false"
        show-icon
        class="prd-alert"
        :title="`有 ${gateBlockTotal} 项草案自检未通过，修正前无法确认分发`"
        description="展开对应行查看「自检明细」，用「编辑」补齐字段或调整验收写法；也可「拒绝重拆」让 AI 重新拆一轮。"
      />

      <!-- 辅助提示区：生成期铺底信息（可关闭 / 站内提醒 / 预计耗时 + 已识别草案数）。
           第二张卡的「产出后站内提醒」当前<b>尚无后端实现</b>（planner 域无通知投递、前端无通知入口），
           但**不是空文案**：方案已在 `doc/design/HelloAI 站内消息与置顶公告设计.md` 定稿（§问题 3 明确
           记录了这一处「空头承诺」，A1 条目负责兑现），实现随后续批次补。此处按产品意图保留文案。 -->
      <div
        v-if="generating"
        class="prd-proc-hints"
      >
        <div class="prd-proc-hint">
          <el-icon class="is-ok"><CircleCheck /></el-icon>
          <div class="prd-proc-hint-text">
            <b>可安全关闭弹窗</b>
            <span>拆解在后台继续，不会中断</span>
          </div>
        </div>
        <div class="prd-proc-hint">
          <el-icon class="is-purple"><Bell /></el-icon>
          <div class="prd-proc-hint-text">
            <b>产出后站内提醒</b>
            <span>草案就绪会推送一条通知</span>
          </div>
        </div>
        <div class="prd-proc-hint">
          <el-icon class="is-orange"><Timer /></el-icon>
          <div class="prd-proc-hint-text">
            <b>预计 30–60 秒</b>
            <span>已识别出 {{ drafts.length }} 个子任务</span>
          </div>
        </div>
      </div>

      <!-- 等待态（仅首请求读取中）：正文照常可读，给轻量进度块（主题变量着色，明暗都够对比） -->
      <div
        v-if="loadingDrafts"
        class="prd-wait"
      >
        <el-icon class="prd-wait-spin">
          <Loading />
        </el-icon>
        <span>正在读取草案…</span>
      </div>

      <!-- 元信息条：草案批次 / 拆解原型 / 生成时间 / 校验结果（原型 4 枚 chip） -->
      <div
        v-if="drafts.length"
        class="prd-meta"
      >
        <span class="prd-meta-chip">
          <span class="k">草案批次</span>
          <span class="v">{{ batchCount }} 批 · {{ drafts.length }} 条</span>
        </span>
        <span class="prd-meta-chip">
          <span class="k">拆解原型</span>
          <span class="v">{{ archetypeLabel }}</span>
        </span>
        <span class="prd-meta-chip">
          <span class="k">生成时间</span>
          <span class="v">{{ fmtTime(drafts[0]?.createTime) }}</span>
        </span>
        <span
          class="prd-meta-chip"
          :class="gateBlockTotal > 0 ? 'is-warn' : 'is-ok'"
        >
          <el-icon class="prd-meta-icon">
            <CircleCheck v-if="gateBlockTotal === 0" />
            <WarningFilled v-else />
          </el-icon>
          <span class="v">{{ gateBlockTotal > 0 ? `${gateBlockTotal} 项待修` : '自检通过' }}</span>
        </span>
      </div>

      <!-- 工具栏：搜索 / 优先级筛选 / 展开全部（原型工具栏左侧搜索+筛选、右侧展开） -->
      <div
        v-if="drafts.length"
        class="prd-toolbar"
      >
        <div class="prd-toolbar-left">
          <el-input
            v-model="keyword"
            size="small"
            class="prd-search"
            placeholder="搜索标题 / 验收标准"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select
            v-model="priorityFilter"
            size="small"
            class="prd-pri-filter"
          >
            <el-option
              label="全部优先级"
              value="ALL"
            />
            <el-option
              v-for="p in priorityOptions"
              :key="p"
              :label="p"
              :value="p"
            />
          </el-select>
        </div>
        <div class="prd-toolbar-right">
          <el-button
            size="small"
            @click="toggleExpandAll"
          >
            {{ allExpanded ? '收起全部' : '展开全部' }}
          </el-button>
        </div>
      </div>

      <el-table
        v-if="drafts.length"
        ref="tableRef"
        :data="filteredDrafts"
        :row-key="draftKey"
        class="prd-table"
        style="width:100%"
      >
        <!-- 行展开：草案详细信息（内容/交付物/验收标准/约束/技能/不确定性明细全文），点击行首箭头展开/收缩 -->
        <el-table-column
          type="expand"
          width="48"
        >
          <template #default="{ row }">
            <div class="draft-detail">
              <div
                v-if="row.content"
                class="dd-sec"
              >
                <div class="dd-label">
                  内容
                </div>
                <div class="dd-text">
                  {{ row.content }}
                </div>
              </div>
              <div
                v-if="row.deliverable"
                class="dd-sec"
              >
                <div class="dd-label">
                  交付物
                </div>
                <div class="dd-text">
                  {{ row.deliverable }}
                </div>
              </div>
              <div
                v-if="row.acceptance"
                class="dd-sec"
              >
                <div class="dd-label">
                  验收标准
                </div>
                <div class="dd-text">
                  {{ row.acceptance }}
                </div>
              </div>
              <div
                v-if="row.constraints"
                class="dd-sec"
              >
                <div class="dd-label">
                  执行约束
                </div>
                <div class="dd-text">
                  {{ row.constraints }}
                </div>
              </div>
              <div
                v-if="row.requiredSkills?.length"
                class="dd-sec"
              >
                <div class="dd-label">
                  技能指派
                </div>
                <div class="dd-tags">
                  <el-tag
                    v-for="s in row.requiredSkills"
                    :key="s"
                    size="small"
                    type="primary"
                  >
                    {{ skillCatalog.labelOf(s) }}
                  </el-tag>
                </div>
              </div>
              <div
                v-if="findingsOf(row).length"
                class="dd-sec"
              >
                <div class="dd-label">
                  自检明细
                </div>
                <div
                  v-for="(f, idx) in findingsOf(row)"
                  :key="idx"
                  class="dd-unc"
                >
                  <el-tag
                    size="small"
                    :type="f.severity === 'block' ? 'danger' : 'warning'"
                  >
                    {{ f.severity === 'block' ? '阻断' : '提示' }}
                  </el-tag>
                  <span>{{ f.evidence }}</span>
                  <span class="dd-rule">{{ f.ruleId }}</span>
                </div>
              </div>
              <div
                v-if="preconditionsOf(row).length"
                class="dd-sec"
              >
                <div class="dd-label">
                  验收前置未满足
                </div>
                <div
                  v-for="(p, idx) in preconditionsOf(row)"
                  :key="idx"
                  class="dd-unc"
                >
                  <el-tag
                    size="small"
                    type="warning"
                  >
                    {{ p.ref }}
                  </el-tag>
                  <span>{{ p.note }}</span>
                </div>
                <div class="dd-hint">
                  该草案的验收要求取回外部实证，但它依赖的事实仍待确认 —— 确认前请先补上这个缺口，或把验收改成「待确认项单独成表、不计 fail」。
                </div>
              </div>
              <div
                v-if="row.uncertainties?.length"
                class="dd-sec"
              >
                <div class="dd-label">
                  不确定性明细
                </div>
                <div
                  v-for="(u, idx) in row.uncertainties"
                  :key="idx"
                  class="dd-unc"
                >
                  <el-tag
                    size="small"
                    :type="u.kind === 'ASSUMPTION' ? 'info' : 'warning'"
                  >
                    {{ u.kind === 'ASSUMPTION' ? '假设' : '待确认' }}
                  </el-tag>
                  <span
                    v-if="u.ref"
                    class="dd-ref"
                  >{{ u.ref }}</span>
                  <span>{{ u.note }}</span>
                  <span
                    v-if="u.blockedBy?.length"
                    class="dd-block"
                  >
                    {{ blockedByText(u.blockedBy, row) }}
                  </span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          label="序号"
          width="72"
        >
          <template #default="{ row }">
            <!-- 层级编号：根层 2，子层 6.2（由 draftCode 推导，与 blockedBy 解析同一套） -->
            <span class="dd-seq">{{ seqOf(row, drafts.indexOf(row)) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="title"
          label="标题"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          v-if="showVerboseColumns"
          prop="content"
          label="内容"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column
          v-if="showVerboseColumns"
          prop="deliverable"
          label="交付物"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          prop="acceptance"
          label="验收标准"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          v-if="showVerboseColumns"
          label="自检"
          width="92"
        >
          <template #default="{ row }">
            <el-tag
              v-if="blockCountOf(row) > 0"
              type="danger"
              size="small"
            >
              {{ blockCountOf(row) }} 项待修
            </el-tag>
            <el-tag
              v-else-if="findingsOf(row).length"
              type="warning"
              size="small"
            >
              提示
            </el-tag>
            <span
              v-else
              class="prd-dash"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="优先级"
          width="96"
        >
          <template #default="{ row }">
            <span
              v-if="row.priority"
              class="prd-pri"
              :class="`is-${(row.priority || '').toLowerCase()}`"
            >{{ row.priority }}</span>
            <span
              v-else
              class="prd-dash"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="依赖"
          width="110"
        >
          <template #default="{ row }">
            <span
              v-if="fmtDepends(row) !== '-'"
              class="prd-dep"
            >{{ fmtDepends(row) }}</span>
            <span
              v-else
              class="prd-dash"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="showVerboseColumns"
          label="技能"
          min-width="120"
        >
          <template #default="{ row }">
            <template v-if="row.requiredSkills?.length">
              <el-tag
                v-for="s in row.requiredSkills"
                :key="s"
                size="small"
                type="primary"
                style="margin-right:4px"
              >
                {{ skillCatalog.labelOf(s) }}
              </el-tag>
            </template>
            <span
              v-else
              class="prd-dash"
            >-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="constraints"
          label="约束"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.constraints || '-' }}
          </template>
        </el-table-column>
        <!-- G-011 不确定性申报列：压缩展示「N 项待确认 · M 项假设」，明细在编辑弹窗内逐条修订 -->
        <el-table-column
          v-if="showVerboseColumns"
          label="不确定性"
          min-width="130"
        >
          <template #default="{ row }">
            {{ fmtUncertainties(row.uncertainties) }}
          </template>
        </el-table-column>
        <!-- 操作列：与任务管理页统一口径 —— 主操作内联按钮 + 次要操作收进「更多」下拉 -->
        <el-table-column
          label="操作"
          width="168"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-cell">
              <el-button
                v-auth="'subtask:edit'"
                size="small"
                type="primary"
                plain
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <!-- 次要操作：统一收进更多下拉 -->
              <el-dropdown
                trigger="click"
                @command="(cmd: string) => handleOpCommand(cmd, row)"
              >
                <el-button
                  size="small"
                  :loading="rowBusy(row)"
                  :aria-label="`草案「${row.title}」更多操作`"
                >
                  更多
                  <el-icon class="el-icon--right">
                    <ArrowDown />
                  </el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <!-- 注意：ElDropdownItem 根节点是 slot fragment，挂在它上面的 v-auth 会被 Vue
                         忽略（静默失效），故下拉项一律用 v-if + hasPermission 过滤 -->
                    <!-- ADR-008 §5 草案派生：一条草案 → 独立任务 Y（Y 拆解完再回到本方案确认） -->
                    <el-dropdown-item
                      v-if="auth.hasPermission('task:plan')"
                      command="derive"
                    >
                      拆出去
                    </el-dropdown-item>
                    <!-- TD-9 单条草案重生成：同粒度重写四要素（title/dependsOn 不变） -->
                    <el-dropdown-item
                      v-if="auth.hasPermission('subtask:edit')"
                      command="regenerate"
                    >
                      重生成
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="!loadingDrafts && !generating"
        :description="emptyText"
      />

      <!-- 表格补充说明（原型：表格下方 12px 提示） -->
      <div
        v-if="drafts.length && filteredDrafts.length < drafts.length"
        class="prd-table-hint"
      >
        已按筛选条件过滤：当前显示 {{ filteredDrafts.length }} / {{ drafts.length }} 条草案。
      </div>
    </div>

    <!-- 底部：左侧汇总信息 + 右侧操作按钮（按钮走任务管理页口径，不用原型图样式） -->
    <template #footer>
      <div class="prd-foot">
        <div class="prd-foot-sum">
          <span>共 <b>{{ drafts.length }}</b> 条草案</span>
          <i class="prd-sep" />
          <span class="prd-high-dot" />
          <span>高优先级 <b>{{ highPriorityCount }}</b> 条</span>
          <template v-if="generating">
            <i class="prd-sep" />
            <el-icon class="prd-foot-clock"><Clock /></el-icon>
            <span>已用时 <b>{{ fmtElapsed }}</b></span>
          </template>
        </div>
        <div class="prd-foot-actions">
          <span
            v-if="generating"
            class="prd-foot-hint"
          >草案产出后可确认</span>
          <el-button
            size="small"
            @click="visible = false"
          >
            取消
          </el-button>
          <el-button
            v-auth="'task:reject-plan'"
            size="small"
            type="danger"
            plain
            :loading="rejecting"
            @click="handleReject"
          >
            拒绝重拆
          </el-button>
          <el-button
            v-auth="'task:confirm-plan'"
            size="small"
            type="primary"
            :loading="confirming"
            :disabled="!drafts.length"
            @click="handleConfirm"
          >
            确认并分发
          </el-button>
        </div>
      </div>
    </template>

    <!-- G-010 草案人工修订：技能指派 + 执行约束（可选编辑，空值放行） -->
    <el-dialog
      v-model="editVisible"
      class="draft-edit-dialog"
      width="640px"
      append-to-body
      :show-close="false"
    >
      <template #header>
        <div class="ped-head">
          <div class="ped-head-main">
            <div class="ped-title-row">
              <h3 class="ped-title">
                修订草案
              </h3>
              <span class="ped-seq">子任务 {{ editIndex + 1 }}/{{ drafts.length }}</span>
            </div>
            <p class="ped-sub">
              {{ editingRow?.title || '' }}
            </p>
          </div>
          <button
            type="button"
            class="prd-close"
            aria-label="关闭"
            @click="editVisible = false"
          >
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </template>

      <el-form
        label-width="88px"
        class="ped-form"
      >
        <el-form-item label="技能标签">
          <el-select
            v-model="editForm.requiredSkills"
            class="ped-skill-select"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入技能标签（留空=不指派）"
            style="width:100%"
          >
            <el-option
              v-for="s in skillOptions"
              :key="s"
              :label="skillCatalog.labelOf(s)"
              :value="s"
            />
          </el-select>
          <div class="ped-field-hint">
            拆解时由 AI 从平台技能目录指派；人工修订不做目录强校验，执行侧按命中技能注入规范，未命中标签自动跳过。
          </div>
        </el-form-item>
        <el-form-item label="执行约束">
          <el-input
            v-model="editForm.constraints"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="不许改的事（边界/红线）；COARSE 粒度建议必填，其余可空"
          />
        </el-form-item>
        <el-form-item label="验收判据">
          <el-select
            v-model="editForm.acceptanceKind"
            style="width:100%"
          >
            <el-option
              v-for="o in acceptanceKindOptions"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <!-- 判据说明区：灰底圆角（原型 #F8F9FB / #EEF0F4） -->
          <div class="ped-tip">
            <div class="ped-tip-line">
              <span class="ped-tip-key">谁来判、怎么判：</span>
              <span>机器判须写命令/断言 + 预期结果；结构判须写可数结构（章节/条目/字数）</span>
            </div>
            <div class="ped-tip-line">
              <span class="ped-tip-key" />
              <span>审查 agent 判须可逐条打勾；人判须写清谁/何时/留什么痕。</span>
            </div>
          </div>
        </el-form-item>
        <!-- G-011 不确定性申报逐条编辑：kind 分级 + note；未填 note 的条目保存时由服务端丢弃 -->
        <el-form-item label="不确定性">
          <div style="width:100%">
            <div
              v-for="(u, idx) in editForm.uncertainties"
              :key="idx"
              class="ped-unc-row"
            >
              <el-select
                v-model="u.kind"
                :class="`is-${(u.kind || '').toLowerCase()}`"
              >
                <el-option
                  label="待确认"
                  value="UNCONFIRMED"
                />
                <el-option
                  label="假设"
                  value="ASSUMPTION"
                />
                <el-option
                  label="待检索"
                  value="TO_LOOK_UP"
                />
                <el-option
                  label="待推导"
                  value="TO_DERIVE"
                />
              </el-select>
              <el-input
                v-model="u.note"
                placeholder="不确定性描述（如：目标表是否含已归档分区）"
              />
              <button
                type="button"
                class="ped-unc-del"
                @click="removeUncertainty(idx)"
              >
                <el-icon><Delete /></el-icon>
                删除
              </button>
            </div>
            <button
              type="button"
              class="ped-add-unc"
              @click="addUncertainty"
            >
              <el-icon><Plus /></el-icon>
              添加一项
            </button>
            <div class="ped-tip is-block">
              <div class="ped-tip-line">
                <span>假设（ASSUMPTION）执行侧可自行验证、推翻即上报；待确认（UNCONFIRMED）须先验证再动手，无法验证则 BLOCKED 上报。保存时未填描述的条目会被丢弃。</span>
              </div>
            </div>
          </div>
        </el-form-item>
        <!-- 优先级与依赖：原型有该行，但修订接口（updateDraftByCode）入参只有
             requiredSkills/constraints/uncertainties/acceptanceKind，无 priority ⇒ 只读展示，
             不做假控件（点了不生效比不显示更坏） -->
        <el-form-item label="优先级">
          <div class="ped-pri-row">
            <span
              v-for="p in PRIORITY_VIEW"
              :key="p.value"
              class="ped-pri"
              :class="[{ 'is-on': editPriority === p.value }, `is-${p.value.toLowerCase()}`]"
            >
              <i class="ped-pri-dot" />{{ p.label }}
            </span>
            <span class="ped-dep">
              <el-icon><Link /></el-icon>
              {{ editDepCount ? `依赖 ${editDepCount} 项` : '当前无依赖' }}
            </span>
          </div>
        </el-form-item>
      </el-form>

      <!-- 风险提示条：琥珀底（原型 #FFF7E6 / #FFE7BA） -->
      <div class="ped-warn">
        <el-icon><WarningFilled /></el-icon>
        <span>修订仅作用于当前草案条目，保存后需回到审阅列表点击「确认并分发」才会转正执行。</span>
      </div>

      <template #footer>
        <div class="ped-foot">
          <span class="ped-foot-hint">
            <el-icon><Clock /></el-icon>
            最近保存：{{ fmtTime(editingRow?.updateTime) }}
          </span>
          <div class="ped-foot-actions">
            <el-button
              class="ped-btn"
              @click="editVisible = false"
            >
              取消
            </el-button>
            <el-button
              class="ped-btn is-primary"
              type="primary"
              :loading="savingDraft"
              @click="saveEdit"
            >
              保存
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document, Close, InfoFilled, Search, CircleCheck, WarningFilled,
  ArrowDown, Delete, Plus, Link, Clock, Loading, Bell, Timer,
} from '@element-plus/icons-vue'
import { taskApi } from '@/api/task'
import { planDraftApi } from '@/api/planDraft'
import { subTaskApi } from '@/api/subTask'
import { useSkillCatalogStore } from '@/stores/skillCatalog'
import { useAuthStore } from '@/stores/auth'
import { TASK_STATUS_MAP } from '@/types/dialog'
import type { Task, SubTask, PlanDraft, LongId, Uncertainty, AcceptanceKind } from '@/types'
import { fmtTime } from '@/utils/tableConfig'
import { sameId } from '@/utils/id'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const skillCatalog = useSkillCatalogStore()
const auth = useAuthStore()
const router = useRouter()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => {
  visible.value = v
  if (v) skillCatalog.ensureLoaded()   // 弹窗打开时才拉技能目录（懒加载）
})
watch(visible, v => {
  emit('update:modelValue', v)
  // 弹窗关闭即停轮询，避免后台定时器持续打接口
  if (!v) stopPolling()
})

const loadingDrafts = ref(false)
const confirming = ref(false)
const rejecting = ref(false)
const drafts = ref<SubTask[]>([])
// 紧凑视图：长文本列（内容/交付物/自检/技能/不确定性）改由行首展开区查看，表内只留决策必需列
const showVerboseColumns = false
// 拆解异步化：草案由后台生成，弹窗打开即进入轮询等待态
const generating = ref(false)
const emptyText = ref('未找到待审阅草案（可能为异常残留），建议「拒绝重拆」使任务回到待规划状态')
// 单条草案派生（拆出去）/ 重生成进行中（按草案 code 记录，防止并发触发同一草案）
const derivingId = ref<string | null>(null)
const regeneratingId = ref<string | null>(null)

const POLL_INTERVAL_MS = 3000
const MAX_WAIT_MS = 5 * 60 * 1000
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollDeadline = 0

/* ── 生成中动态卡片（参考原型 4 态）──
   后端暂不上报阶段进度，故步骤/百分比由真实信号推算：
   已用时（真实计时）+ 已拆出草案数（真实轮询值）→ 展示态；
   文案轮播是纯装饰文案，与「草案产出」的真实进度解耦。 */
const PROC_STEPS = [
  { label: '解析任务目标', pct: 22 },
  { label: '拆解子任务', pct: 46 },
  { label: '校验依赖关系', pct: 68 },
  { label: '生成草案字段', pct: 88 },
] as const
const PROC_MSGS = [
  '正在生成最终草案预览...',
  'AI 正在努力分析任务草案中...',
  '正在努力构建子任务中...',
  '正在努力斟酌任务细节中...',
] as const
const CAROUSEL_MS = 3500
const elapsedSec = ref(0)
const carouselIdx = ref(0)
let procTimers: ReturnType<typeof setInterval>[] = []

const fmtElapsed = computed(() => {
  const s = elapsedSec.value
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})
const stepIndex = computed(() => {
  const e = elapsedSec.value
  const n = drafts.value.length
  if (e >= 30 || n >= 5) return 3
  if (e >= 20 || n >= 3) return 2
  if (e >= 10 || n >= 1) return 1
  return 0
})
const carouselMsgs = computed(() => {
  const i = carouselIdx.value
  return {
    prev: PROC_MSGS[(i + PROC_MSGS.length - 1) % PROC_MSGS.length],
    cur: PROC_MSGS[i],
    next: PROC_MSGS[(i + 1) % PROC_MSGS.length],
  }
})
function startProcTimers() {
  if (procTimers.length) return
  elapsedSec.value = 0
  carouselIdx.value = 0
  procTimers.push(setInterval(() => { elapsedSec.value++ }, 1000))
  procTimers.push(setInterval(() => {
    carouselIdx.value = (carouselIdx.value + 1) % PROC_MSGS.length
  }, CAROUSEL_MS))
}
function stopProcTimers() {
  procTimers.forEach(clearInterval)
  procTimers = []
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  generating.value = false
  stopProcTimers()
}

async function loadDrafts() {
  if (!props.task) return
  stopPolling()
  loadingDrafts.value = true
  drafts.value = []
  pollDeadline = Date.now() + MAX_WAIT_MS
  await pollOnce()
}

// 轮询单步：草案为空且任务仍在拆解（PLANNING）时继续等待；
// 任务已回退 PENDING（拆解失败/租约过期被回收）则停止并提示重试。
// 草案一旦产出，任务转 AWAITING_PLAN_CONFIRM，上面的 `list.length` 分支先命中直接返回，
// 所以这里不必显式判断新状态。
async function pollOnce() {
  const task = props.task
  if (!task || !visible.value) { stopPolling(); return }
  try {
    const [planList, latest] = await Promise.all([
      planDraftApi.listByTask(String(task.id)),
      taskApi.getById(String(task.id))
    ])
    // AD-5a-3：优先读暂存区；空列表（存量 83 条）回落旧接口
    const list = planList.length ? planList.map(toDraftRow) : await taskApi.planDrafts(String(task.id))
    if (!visible.value) return
    if (list.length) {
      drafts.value = list
      generating.value = false
      loadingDrafts.value = false
      stopProcTimers()
      return
    }
    if (latest?.status === 'PENDING') {
      generating.value = false
      loadingDrafts.value = false
      stopProcTimers()
      emptyText.value = '拆解失败，任务已回到待规划状态，请回任务列表重试'
      ElMessage.warning('拆解失败，请回任务列表重试')
      return
    }
    if (latest?.status !== 'PLANNING' || Date.now() >= pollDeadline) {
      generating.value = false
      loadingDrafts.value = false
      stopProcTimers()
      return
    }
    // 仍在拆解中：进入等待态，3s 后继续轮询
    // 首请求已返回，loadingDrafts 必须复位——否则它会一直为 true，等待块文案停在「正在读取草案…」，
    // 且旧实现里正是这个未复位的标记让 v-loading 遮罩盖了整个等待期（文字看不清的根因）。
    loadingDrafts.value = false
    generating.value = true
    startProcTimers()
    pollTimer = setTimeout(pollOnce, POLL_INTERVAL_MS)
  } catch {
    // 拦截器已弹错；不继续轮询避免报错刷屏
    generating.value = false
    loadingDrafts.value = false
    stopProcTimers()
  }
}

onBeforeUnmount(stopPolling)

// ── 工具栏：搜索 / 优先级筛选 / 展开全部（均为纯前端，不新增后端契约） ──
const keyword = ref('')
const priorityFilter = ref('ALL')
const tableRef = ref<any>(null)
const allExpanded = ref(false)

const priorityOptions = computed(() => {
  const set = new Set<string>()
  drafts.value.forEach(d => { if (d.priority) set.add(d.priority) })
  return [...set]
})

// 标题/验收标准命中即保留；优先级筛选按精确值匹配
const filteredDrafts = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return drafts.value.filter(d => {
    if (priorityFilter.value !== 'ALL' && d.priority !== priorityFilter.value) return false
    if (!kw) return true
    return (d.title || '').toLowerCase().includes(kw)
      || (d.acceptance || '').toLowerCase().includes(kw)
  })
})

// 全部展开/收起：只作用于当前筛选结果，避免把隐藏行也拉成展开态
function toggleExpandAll() {
  const next = !allExpanded.value
  filteredDrafts.value.forEach(row => {
    tableRef.value?.toggleRowExpansion(row, next)
  })
  allExpanded.value = next
}

// ── 元信息条（原型 4 枚 chip）：全部取自现有字段，不新增接口 ──
const archetypeLabel = computed(() => {
  const map: Record<string, string> = {
    BUILD: '构建类',
    DOCUMENT: '文档类',
    RESEARCH: '研究类',
    DATA: '数据类',
    OPERATE: '运营类',
    DECIDE: '决策类'
  }
  const t = props.task?.taskArchetype
  return t ? (map[t] || t) : '构建类'
})

// 批次数量：同一批拆解产出共享 draftCode 前缀（batchKeyOf），去重即批数
const batchCount = computed(() => {
  const set = new Set<string>()
  for (const d of drafts.value) {
    const b = batchKeyOf(d.draftCode)
    if (b) set.add(b)
  }
  return set.size || (drafts.value.length ? 1 : 0)
})

const highPriorityCount = computed(() =>
  drafts.value.filter(d => {
    const p = (d.priority || '').toUpperCase()
    return p === 'HIGH' || p === 'P0'
  }).length)

// 暂存区草案 → 既有表格行形状（id 用暂存区 id；写路径仍以 code 定位，兼容到 5a-5）
function toDraftRow(d: PlanDraft): SubTask {
  return {
    id: d.id, taskId: d.taskId, moduleId: null, title: d.title,
    status: 'PENDING', assignedAgent: null, content: d.content || '',
    deliverable: d.deliverable ?? null, acceptance: d.acceptance ?? null,
    acceptanceKind: d.acceptanceKind ?? null, priority: d.priority ?? null,
    requiredSkills: d.requiredSkills ?? null, constraints: d.constraints ?? null,
    uncertainties: d.uncertainties ?? null, dependsOn: [],
    dependsOnCodes: d.dependsOnCodes ?? [], context: d.context ?? null,
    scoreFactors: null, compositeScore: null, scoreGrade: null, deadline: null,
    reworkCount: 0, timeoutCount: 0, draftCode: d.code, draftSeq: d.draftSeq ?? null,
    createTime: d.updateTime || '', updateTime: d.updateTime || ''
  } as SubTask
}

// dependsOn 存草案 id，映射为表内序号展示（如「依赖 #1,#2」）
function fmtDepends(row: SubTask): string {
  // AD-5a-3：暂存区行用 dependsOnCodes；展示沿用「批内序号」
  const codes = row.dependsOnCodes
  if (codes?.length) {
    const seqs = codes.map(code => {
      const hit = drafts.value.find(d => d.draftCode === code)
      const seq = (hit as any)?.draftSeq ?? Number(code.slice(-4))
      return Number.isFinite(seq) ? seq : null
    }).filter((n): n is number => n != null)
    return seqs.length ? `依赖 #${seqs.join(',')}` : '-'
  }
  const dependsOn = row.dependsOn
  if (!dependsOn?.length) return '-'
  const seqs = dependsOn
    .map(id => drafts.value.findIndex(d => sameId(d.id, id)) + 1)
    .filter(seq => seq > 0)
  return seqs.length ? `依赖 #${seqs.join(',')}` : '-'
}

// G-011 不确定性压缩展示：「N 项待确认 · M 项假设 · K 项待查/待推」（全空时显示 -）
function fmtUncertainties(uncertainties?: Uncertainty[] | null): string {
  if (!uncertainties?.length) return '-'
  const pending = uncertainties.filter(u => u.kind === 'UNCONFIRMED').length
  const assumed = uncertainties.filter(u => u.kind === 'ASSUMPTION').length
  const lookup = uncertainties.filter(u => u.kind === 'TO_LOOK_UP' || u.kind === 'TO_DERIVE').length
  const parts: string[] = []
  if (pending > 0) parts.push(`${pending} 项待确认`)
  if (assumed > 0) parts.push(`${assumed} 项假设`)
  if (lookup > 0) parts.push(`${lookup} 项待查/待推`)
  return parts.length ? parts.join(' · ') : `${uncertainties.length} 项`
}

/**
 * 草案自检结论（草案实体化）：挂在 `sub_task.context.gateFindings`，
 * 形状 `{ findings: [{ruleId, severity, evidence, subject}], ruleVersion, checkedAt|clearedAt }`。
 * `findings` 为空 = 无问题或已清零；只有 `severity='block'` 会挡住确认/分发。
 *
 * 放在草案上而不是任务上，是因为结论描述的是「这一条哪里不合格」——挂任务上只能整体清、
 * 也无法在行内定位。
 */
interface GateFinding {
  ruleId: string
  severity: string
  evidence: string
  subject?: string
}

function findingsOf(row: SubTask): GateFinding[] {
  const list = (row.context as any)?.gateFindings?.findings
  return Array.isArray(list) ? (list as GateFinding[]) : []
}

function blockCountOf(row: SubTask): number {
  return findingsOf(row).filter(f => f.severity === 'block').length
}

/**
 * `TD-11` 验收前置未满足（首处读取方）：`context.acceptancePreconditions`
 * 是拆解侧写下的缺口 `ref` 数组，含义是「这条草案的验收要求取回外部实证，但它依赖的事实
 * 仍是 UNCONFIRMED」。
 *
 * 在此之前该键**全仓零读取方** —— 标对没人用、标错没人受影响，这正是它准确率长期为 0
 * 却没人发现的唯一解释。这里只做「摊到人眼前」，不参与任何判定（豁免判定属 `TD-12`）。
 *
 * `ref` 解析不到时仍返回该条（note 留空），宁可显示一个孤儿标签也不静默吞掉——
 * 落库侧已有 `allRefsResolvable` 守卫，走到这里解析不到说明存量行或人工编辑改过缺口。
 */
function preconditionsOf(row: SubTask): { ref: string; note: string }[] {
  const refs = (row.context as any)?.acceptancePreconditions
  if (!Array.isArray(refs) || !refs.length) return []
  return (refs as string[]).map((ref) => {
    const hit = (row.uncertainties || []).find(u => u.ref === ref)
    return { ref, note: hit?.note || '（本草案内未找到该缺口，可能已被编辑删除）' }
  })
}

/**
 * 位置编号的层级片段：`202611080002` → `['2']`；`202611080006-002` → `['6','2']`。
 *
 * <p><b>为什么行首编号不能直接用 `draftSeq`</b>：`draftSeq` 是**批内序号**
 * （后端 `buildDrafts` 写死 `idx + 1`；`dependsOn` 里的整数与
 * `uk_sub_task_decompose_attempt` 都依赖这个语义，不能改全局号）。首拆一批 1..N、
 * 每次展开另起一批 1..N，于是同一张表里会出现两个 `#2`——任务
 * `2119434200503037953` 即如此（根批 `...0002` 与展开批 `...0006-002`）。
 * `code` 才是任务内的位置身份，`-` 天然分层，故行首编号由它推导。</p>
 */
function codeSegments(code?: string | null): string[] {
  if (!code) return []
  const segs = code.split('-')
  const head = segs[0]
  // 根段 = 日期(8) + 序号(4)，只取序号；短于此的原样保留（防御脏数据）
  const nums = [head.length > 4 ? head.slice(-4) : head, ...segs.slice(1)]
  if (nums.some(s => !/^\d+$/.test(s))) return []
  return nums.map(s => String(Number(s)))
}

/** 层级编号（`2` / `6.2` / `6.2.1`）；存量草案无 `code` 或不可解析时返回 null。 */
function labelOfDraft(row: SubTask): string | null {
  const segs = codeSegments(row.draftCode)
  return segs.length ? segs.join('.') : null
}

/**
 * 批标识：同一批拆解产出的草案共享它，`draftSeq` 只在批内唯一。
 *
 * <p>根层（无层级后缀）用日期段——同一任务的 ACTIVE 根草案必属同一轮拆解
 * （驳回重拆会把上一轮置 `DISCARDED`，不进本表）；子层用「去掉最后一段的父 code」。
 * 两者空间不相交（8 位 vs 12 位以上），不会串批。</p>
 */
function batchKeyOf(code?: string | null): string | null {
  if (!code) return null
  const cut = code.lastIndexOf('-')
  if (cut < 0) return code.length >= 8 ? code.slice(0, 8) : code
  return code.slice(0, cut)
}

/**
 * 「批内序号 → 行」映射，key = `批标识#序号`。
 *
 * <p><b>为什么必须按批限定</b>：`uncertainty.blockedBy` 里的整数是**本行所属批**的
 * 批内序号。旧实现用 `Map<seq, title>` 直接覆盖，出现两个 `#2` 时后写者赢，
 * 「卡住 #2」会锚到另一批的行——标注等于错指（本表按依赖拓扑排序，行号也不可用）。</p>
 */
const draftTitleBySeq = computed(() => {
  const m = new Map<string, { label: string; title: string }>()
  for (const d of drafts.value) {
    const batch = batchKeyOf(d.draftCode)
    const seq = (d as any).draftSeq
    if (!batch || typeof seq !== 'number') continue
    m.set(`${batch}#${seq}`, { label: labelOfDraft(d) ?? String(seq), title: d.title })
  }
  return m
})

/** 把 `blockedBy` 的序号换成可读文案（命中则用被命中行的层级编号，否则回落序号）。 */
function blockedByText(seqs: number[], row: SubTask): string {
  const batch = batchKeyOf(row.draftCode)
  const parts = seqs.map((seq) => {
    const hit = batch ? draftTitleBySeq.value.get(`${batch}#${seq}`) : undefined
    return hit ? `#${hit.label}「${hit.title}」` : `#${seq}`
  })
  return `卡住 ${parts.join('、')}`
}

/** 行首编号：优先 `draftCode` 推导的层级编号，存量无 code 回落批内序号，再回落行号。 */
function seqOf(row: SubTask, index: number): number | string {
  const label = labelOfDraft(row)
  if (label) return label
  const seq = (row as any).draftSeq
  return typeof seq === 'number' ? seq : index + 1
}

/**
 * 草案行的稳定身份（`d-3b-3`）：优先位置编号 `code`，存量无 code 回落行 id。
 *
 * 为什么不用 id：展开会删父行重建，同一个位置换了一行——用 id 做 row-key 会让
 * 展开后的整表按新 id 重挂，行展开态与按钮 loading 态全部错位；code 表达的是
 * 「这是第几个位置」，重建后不变。
 */
function draftKey(row: SubTask): string {
  return row.draftCode || String(row.id)
}

const gateBlockTotal = computed(() =>
  drafts.value.reduce((sum, d) => sum + blockCountOf(d), 0))

// TD-2 验收判据下拉（「谁来判、怎么判」；默认 AUTOMATED = 改造前行为）
const acceptanceKindOptions = [
  { value: 'AUTOMATED', label: '机器判（命令/断言）' },
  { value: 'STRUCTURAL', label: '结构判（章节/条目/字数）' },
  { value: 'REVIEWED', label: '审查 agent 判（逐条打勾）' },
  { value: 'ATTESTED', label: '人判（谁/何时/留什么痕）' }
]

// --- G-010 草案人工修订（技能指派 + 执行约束） ---
// --- G-011 扩展：不确定性逐条编辑（kind + note，空行保存时由服务端丢弃） ---
const editVisible = ref(false)
const savingDraft = ref(false)
const editingRow = ref<SubTask | null>(null)
const editForm = ref<{ requiredSkills: string[]; constraints: string; acceptanceKind: string; uncertainties: { kind: string; note: string }[] }>(
  { requiredSkills: [], constraints: '', acceptanceKind: 'AUTOMATED', uncertainties: [] }
)

// 编辑弹窗序号徽标：当前草案在列表中的位置（找不到回 0，模板 +1 显示）
const editIndex = computed(() => {
  const row = editingRow.value
  if (!row) return 0
  const i = drafts.value.findIndex(d => draftKey(d) === draftKey(row))
  return i < 0 ? 0 : i
})

// 优先级只读展示：修订接口无 priority 入参，这里按原型渲染三个档位（当前值高亮）
const PRIORITY_VIEW = [
  { value: 'HIGH', label: 'HIGH' },
  { value: 'MEDIUM', label: 'MED' },
  { value: 'LOW', label: 'LOW' },
] as const
const editPriority = computed(() => String((editingRow.value as { priority?: string } | null)?.priority || '').toUpperCase())
const editDepCount = computed(() => {
  const deps = (editingRow.value as { dependsOn?: unknown } | null)?.dependsOn
  return Array.isArray(deps) ? deps.length : 0
})

// 技能下拉候选：平台技能目录（服务端下发，REF-1.2c）+ 全部草案已指派标签的并集（allow-create 兜自由输入）
const skillOptions = computed(() => {
  const set = new Set<string>()
  skillCatalog.options.forEach(o => set.add(o.value))
  drafts.value.forEach(d => d.requiredSkills?.forEach(s => set.add(s)))
  return [...set]
})

function openEdit(row: SubTask) {
  editingRow.value = row
  editForm.value = {
    requiredSkills: [...(row.requiredSkills ?? [])],
    constraints: row.constraints ?? '',
    acceptanceKind: row.acceptanceKind ?? 'AUTOMATED',
    uncertainties: (row.uncertainties ?? []).map(u => ({ kind: u.kind ?? 'UNCONFIRMED', note: u.note ?? '' }))
  }
  editVisible.value = true
}

// 新增一条默认「待确认」空行（note 留空保存时服务端丢弃）
function addUncertainty() {
  editForm.value.uncertainties.push({ kind: 'UNCONFIRMED', note: '' })
}

function removeUncertainty(idx: number) {
  editForm.value.uncertainties.splice(idx, 1)
}

async function saveEdit() {
  const row = editingRow.value
  if (!row) return
  savingDraft.value = true
  try {
    // 保存前过滤空行（与服务端空白 note 丢弃口径一致），kind 下拉限定四值无需强校验
    const uncertainties = editForm.value.uncertainties
      .filter(u => u.note.trim() !== '')
      .map(u => ({ kind: u.kind, note: u.note }))
    const payload = {
      requiredSkills: editForm.value.requiredSkills,
      constraints: editForm.value.constraints,
      uncertainties,
      acceptanceKind: editForm.value.acceptanceKind as AcceptanceKind
    }
    // 暂存区行必有位置编号；缺失说明数据/页面已过期，不能静默当成功
    if (!row.draftCode || !props.task) {
      ElMessage.error('草案缺少位置编号，请刷新后重试')
      return
    }
    await subTaskApi.updateDraftByCode(props.task.id, row.draftCode, payload)
    // 本地同步，免整表重拉
    row.requiredSkills = [...editForm.value.requiredSkills]
    row.constraints = editForm.value.constraints
    row.acceptanceKind = editForm.value.acceptanceKind as AcceptanceKind
    row.uncertainties = uncertainties
    ElMessage.success('草案已修订')
    editVisible.value = false
  } catch { /* 拦截器已弹错 */ }
  finally { savingDraft.value = false }
}

async function handleConfirm() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      `将 ${drafts.value.length} 条草案转正为待分配子任务，任务进入「进行中」并按配置自动分配执行 Agent。是否继续？`,
      '确认拆解方案',
      { type: 'warning', confirmButtonText: '确认并分发', cancelButtonText: '取消' }
    )
  } catch { return }
  confirming.value = true
  try {
    await taskApi.confirmPlan(String(props.task.id))
    ElMessage.success(`已确认 ${drafts.value.length} 条草案并开始分发`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { confirming.value = false }
}

async function handleReject() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      `将作废「${props.task.title}」的全部草案，任务回到「待规划」可重新拆解。`
      + '注意：由本方案草案「拆出去」的任务会一并取消（它们的需求依据已作废）。是否继续？',
      '拒绝重拆',
      { type: 'warning', confirmButtonText: '拒绝重拆', cancelButtonText: '取消' }
    )
  } catch { return }
  rejecting.value = true
  try {
    const res = await taskApi.rejectPlan(String(props.task.id))
    ElMessage.success(`已作废 ${res.cancelledCount} 条草案，任务已回到待规划`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { rejecting.value = false }
}

// --- ADR-008 §5 草案派生（对话版）：一条草案 → 先开派生会话 ---
// 旧实现直接建 Y 并让 Y 立刻自拆：全程没人问过「仓库在哪 / 执行机有什么运行时 /
// 数据源是哪种」，Y 拆出来的子任务只能写「仓库中」，执行侧必然因缺仓库检出而人工介入。
// 现在改为进对话——把草案四要素 + 上下游草案 + 已声明的执行环境交给 Planner，
// 问清执行前提、确认终稿之后才建独立任务。
async function handleDerive(row: SubTask) {
  if (derivingId.value) { ElMessage.warning('已有草案在派生中，请稍候'); return }
  if (!props.task || !row.draftCode) {
    ElMessage.error('该草案缺少位置编号，无法派生（存量草案待迁移）')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将「${row.title}」拆出去：先进入需求对话确认执行前提（仓库 / 运行时 / 数据源），确认终稿后才建独立任务。原草案保留，确认本方案时再物化。`,
      '拆出去',
      { type: 'warning', confirmButtonText: '进入对话', cancelButtonText: '取消' }
    )
  } catch { return }
  derivingId.value = draftKey(row)
  try {
    const conversationId = await subTaskApi.startDraftDeriveSession(props.task.id, row.draftCode)
    ElMessage.success('已开派生会话，请先在对话中确认执行前提')
    visible.value = false
    router.push({ path: '/requirement-chat', query: { id: String(conversationId) } })
  } catch { /* 拦截器已弹错 */ }
  finally { derivingId.value = null }
}

// --- TD-9 单条草案重生成（regenerate）：同粒度重写四要素（title/dependsOn 不变量） ---
// 后端只取 content/deliverable/acceptance/acceptanceKind 四字段，其余保持原值
async function handleRegenerate(row: SubTask) {
  if (regeneratingId.value) { ElMessage.warning('已有草案在重生成中，请稍候'); return }
  try {
    await ElMessageBox.confirm(
      `将按同粒度重新生成「${row.title}」的内容/交付物/验收标准（标题与依赖关系保持不变）。是否继续？`,
      '重生成草案',
      { type: 'warning', confirmButtonText: '重生成', cancelButtonText: '取消' }
    )
  } catch { return }
  regeneratingId.value = draftKey(row)
  try {
    if (!row.draftCode || !props.task) {
      ElMessage.error('草案缺少位置编号，请刷新后重试')
      return
    }
    const updated = await subTaskApi.regenerateDraftByCode(props.task.id, row.draftCode)
    ElMessage.success('草案已按新内容重生成')
    // 局部刷新：替换重生成后的四要素（title/dependsOn 不变量由后端保证）
    const idx = drafts.value.findIndex(d => draftKey(d) === draftKey(row))
    if (idx >= 0) {
      const merged = { ...row, ...updated }
      drafts.value.splice(idx, 1, merged)
    }
  } catch { /* 拦截器已弹错 */ }
  finally { regeneratingId.value = null }
}

// 行级忙碌态：派生/重生成进行中时「更多」按钮显示 loading（下拉已关闭，靠它给反馈）
function rowBusy(row: SubTask): boolean {
  const k = draftKey(row)
  return derivingId.value === k || regeneratingId.value === k
}

function handleOpCommand(cmd: string, row: SubTask) {
  if (cmd === 'derive') handleDerive(row)
  else if (cmd === 'regenerate') handleRegenerate(row)
}
</script>

<style scoped>
/* ============================================================
   弹窗外壳：对齐设计稿「弹窗卡片」
   白底 / 圆角 18 / 1px 边框 #EDEFF3 / 阴影 0 16 40 rgba(15,23,42,.14)
   append-to-body 落到 body，作用域样式用 :global 限定本弹窗类名
   ============================================================ */
:global(.plan-review-dialog) {
  /* 走 Element 变量而非直接写 border-radius：全局 .el-dialog 用变量指定圆角且带 !important */
  --el-dialog-border-radius: 18px !important;
  border: 1px solid var(--ha-border-light);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.14) !important;
  overflow: hidden;
}
/* 全局 design-system.css 的 header/body/footer padding 带 !important，覆盖必须同权 */
:global(.plan-review-dialog .el-dialog__header) {
  padding: 0 !important;
  margin: 0 !important;
  border-bottom: none !important;
}
:global(.plan-review-dialog .el-dialog__body) {
  padding: 0 !important;
}
:global(.plan-review-dialog .el-dialog__footer) {
  padding: 0 !important;
  border-top: none !important;
  background: transparent !important;
}

/* ---- 头部（padding 28/32/20/32，图标 40 圆角 12，标题 18px） ---- */
.prd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 28px 32px 20px;
}
.prd-head-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.prd-head-icon {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--ha-primary-light);
  color: var(--ha-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}
.prd-head-text {
  min-width: 0;
}
.prd-head-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prd-head-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
  color: var(--ha-ink);
}
/* 状态徽标：11px / 圆角 6（琥珀=待确认，紫=拆解中） */
.prd-badge {
  flex: none;
  padding: 0 8px;
  height: 20px;
  line-height: 18px;
  border-radius: 6px;
  font-size: 11px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.prd-badge.is-waiting {
  background: var(--ha-warning-bg);
  border-color: rgba(245, 158, 11, 0.35);
  color: var(--ha-warning-text);
}
.prd-badge.is-progress {
  background: var(--ha-primary-light);
  border-color: rgba(124, 58, 237, 0.25);
  color: var(--ha-primary);
}
.prd-head-sub {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--ha-ink-secondary);
}
/* 关闭按钮：32×32 圆角 8（与设计稿一致） */
.prd-close {
  flex: none;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ha-muted);
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 150ms ease, color 150ms ease;
}
.prd-close:hover {
  background: var(--ha-surface-hover);
  color: var(--ha-ink);
}

/* ---- 主体（padding 24/32/24/32） ---- */
.prd-body {
  padding: 0 32px 24px;
}

/* 说明条：#F7F5FF 底 / 圆角 12 / 1px 边框 #EADDFF / padding 14 16 */
.prd-note {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--ha-primary-light);
  border: 1px solid rgba(124, 58, 237, 0.16);
  font-size: 13px;
  line-height: 22px;
  color: var(--ha-ink-secondary);
}
.prd-note-icon {
  flex: none;
  margin-top: 3px;
  color: var(--ha-primary);
  font-size: 16px;
}
.prd-alert {
  margin-top: 12px;
  border-radius: 12px;
}

/* ---- 生成中动态卡片（参考原型：AI 处理中卡片） ---- */
.prd-proc {
  margin-top: 12px;
  padding: 20px 24px 0;
  border: 1px solid var(--ha-border);
  border-radius: 16px;
  background: var(--ha-surface-elevated);
  box-shadow: var(--ha-shadow-sm);
}
.prd-proc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.prd-proc-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prd-proc-title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ha-primary);
  animation: prd-proc-pulse 1.6s ease-in-out infinite;
}
.prd-proc-title-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--ha-ink);
}
.prd-proc-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  border-radius: 6px;
  background: var(--ha-primary-light);
  font-size: 12px;
  font-weight: 500;
  color: var(--ha-primary);
  white-space: nowrap;
}
.prd-proc-badge-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
.prd-proc-main {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: 16px;
}
.prd-ring {
  flex: none;
  width: 64px;
}
.prd-ring svg {
  display: block;
  width: 50px;
  height: 50px;
  margin: 0 auto;
  animation: prd-ring-rotate 2.4s linear infinite;
}
.prd-ring-track { stroke: var(--ha-border); }
.prd-ring-arc {
  stroke: var(--ha-primary);
  filter: drop-shadow(0 0 3px rgba(124, 58, 237, 0.35));
}
.prd-ring-disc { fill: var(--ha-primary-light); }
.prd-ring-core { fill: var(--ha-primary); }

/* 文案轮播视窗 */
.prd-carousel {
  flex: 1;
  min-width: 0;
}
.prd-carousel-window {
  display: flex;
  flex-direction: column;
  min-height: 104px;
  justify-content: center;
  padding: 14px 20px;
  border-radius: 12px;
  background: var(--ha-surface-hover);
}
.prd-carousel-line {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ha-muted);
}
.prd-carousel-bullet {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.7;
}
.prd-carousel-msg.is-sm {
  font-size: 13px;
}
.prd-carousel-current {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}
.prd-carousel-bar {
  flex: none;
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: var(--ha-primary);
}
.prd-carousel-msg {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.35;
}
.prd-carousel-msg.is-cur {
  font-size: 16px;
  font-weight: 600;
  color: var(--ha-ink);
}
.prd-carousel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}
.prd-carousel-dots {
  display: flex;
  align-items: center;
  gap: 6px;
}
.prd-carousel-dot {
  width: 7px;
  height: 6px;
  border-radius: 3px;
  background: var(--ha-border);
  transition: width 150ms var(--ha-ease-out), background 150ms var(--ha-ease-out);
}
.prd-carousel-dot.is-cur {
  width: 21px;
  background: var(--ha-primary);
}
.prd-carousel-hint {
  font-size: 12px;
  color: var(--ha-muted);
  white-space: nowrap;
}

/* 进度区 */
.prd-proc-progress {
  margin-top: 20px;
}
.prd-proc-progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ha-ink-secondary);
}
.prd-proc-progress-head b {
  font-weight: 600;
  color: var(--ha-primary);
}
.prd-proc-track {
  height: 6px;
  margin-top: 8px;
  border-radius: 3px;
  background: var(--ha-border);
  overflow: hidden;
}
.prd-proc-track-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--ha-primary);
  transition: width 400ms var(--ha-ease-out);
}

/* 步骤条 */
.prd-proc-steps {
  display: flex;
  align-items: flex-start;
  margin-top: 16px;
  padding-bottom: 20px;
}
.prd-proc-connector {
  flex: 1;
  height: 2px;
  margin: 9px 8px 0;
  border-radius: 1px;
  background: var(--ha-border);
  transition: background 250ms var(--ha-ease-out);
}
.prd-proc-connector.is-done {
  background: var(--ha-primary);
  opacity: 0.55;
}
.prd-proc-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 96px;
}
.prd-proc-step-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  transition: background 150ms var(--ha-ease-out), color 150ms var(--ha-ease-out);
}
.prd-proc-step.is-done .prd-proc-step-dot {
  background: var(--ha-success-bg);
  color: var(--ha-success-text);
}
.prd-proc-step.is-cur .prd-proc-step-dot {
  background: var(--ha-surface);
  box-shadow: 0 0 0 1.5px var(--ha-primary) inset;
}
.prd-proc-step.is-cur .prd-proc-step-dot::after {
  content: '';
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ha-primary);
}
.prd-proc-step.is-pending .prd-proc-step-dot {
  background: var(--ha-surface-hover);
  color: var(--ha-muted);
  box-shadow: 0 0 0 1px var(--ha-border) inset;
}
.prd-proc-step-check {
  width: 10px;
  height: 8px;
}
.prd-proc-step-label {
  font-size: 12.5px;
  text-align: center;
  line-height: 18px;
  color: var(--ha-muted);
}
.prd-proc-step.is-cur .prd-proc-step-label {
  font-weight: 600;
  color: var(--ha-primary);
}
.prd-proc-step.is-done .prd-proc-step-label {
  color: var(--ha-ink-secondary);
}

/* 辅助提示区：三张提示卡 */
.prd-proc-hints {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 16px;
}
.prd-proc-hint {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
  padding: 12px 16px;
  border: 1px solid var(--ha-border);
  border-radius: 10px;
  background: var(--ha-surface-hover);
}
.prd-proc-hint > .el-icon {
  flex: none;
  margin-top: 2px;
  font-size: 16px;
}
.prd-proc-hint > .el-icon.is-ok { color: var(--ha-success-text); }
.prd-proc-hint > .el-icon.is-purple { color: var(--ha-primary); }
.prd-proc-hint > .el-icon.is-orange { color: var(--ha-warning-text); }
.prd-proc-hint-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.prd-proc-hint-text b {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ha-ink);
}
.prd-proc-hint-text span {
  margin-top: 2px;
  font-size: 12px;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 底部生成期附加信息 */
.prd-foot-clock {
  font-size: 13px;
  color: var(--ha-muted);
}
.prd-foot-hint {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--ha-muted);
  margin-right: 4px;
}

@keyframes prd-ring-rotate {
  to { transform: rotate(360deg); }
}
@keyframes prd-proc-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.45; transform: scale(0.8); }
}
@media (prefers-reduced-motion: reduce) {
  .prd-ring svg { animation: none; }
  .prd-proc-title-dot { animation: none; }
}

/* 等待态块：虚线边框 + 次级底色，文字色走 --ha-ink-secondary（亮 #4A5568 / 暗 #A9B4C7），
   不依赖 Element Plus 遮罩的 rgba 覆盖，因此明暗两套主题都不存在「被冲淡」的问题 */
.prd-wait {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 16px;
  padding: 30px 16px;
  border: 1px dashed var(--ha-border);
  border-radius: 12px;
  background: var(--ha-surface-hover);
  font-size: 13px;
  line-height: 20px;
  color: var(--ha-ink-secondary);
}
.prd-wait-spin {
  font-size: 18px;
  color: var(--ha-primary);
  animation: prd-wait-rotate 0.9s linear infinite;
}
@keyframes prd-wait-rotate {
  to { transform: rotate(360deg); }
}
/* 尊重系统「减少动效」偏好：不转圈，靠文案表达进行中 */
@media (prefers-reduced-motion: reduce) {
  .prd-wait-spin { animation: none; }
}

/* 元信息条：chip 圆角 8 / 高 32 / 字号 12 */
.prd-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.prd-meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--ha-surface-hover);
  font-size: 12px;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
}
.prd-meta-chip .k {
  color: var(--ha-muted);
}
.prd-meta-chip .v {
  font-weight: 500;
  color: var(--ha-ink);
}
.prd-meta-chip.is-ok {
  background: var(--ha-success-bg);
  border: 1px solid rgba(16, 185, 129, 0.3);
}
.prd-meta-chip.is-ok .v,
.prd-meta-chip.is-ok .prd-meta-icon {
  color: var(--ha-success-text);
}
.prd-meta-chip.is-warn {
  background: var(--ha-warning-bg);
  border: 1px solid rgba(245, 158, 11, 0.35);
}
.prd-meta-chip.is-warn .v,
.prd-meta-chip.is-warn .prd-meta-icon {
  color: var(--ha-warning-text);
}

/* 工具栏：左侧搜索 + 筛选，右侧展开全部 */
.prd-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
}
.prd-toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prd-search {
  width: 240px;
}
.prd-pri-filter {
  width: 132px;
}

/* ---- 表格：圆角 10 / 1px 边框 / 表头 44 高灰底 / 行内 padding 12 ---- */
/* class="prd-table" 直接挂在 <el-table> 根节点上，自身即 .el-table —— 圆角/边框写在自身（scoped 可命中根），
   单元格是后代，用 :deep(th/td.el-table__cell) 命中 */
.prd-table {
  margin-top: 12px;
  border-radius: 10px;
  border: 1px solid var(--ha-border-light);
  overflow: hidden;
}
.prd-table :deep(th.el-table__cell) {
  height: 44px !important;
  padding: 0 !important;
  background: var(--ha-surface-hover) !important;
  font-size: 12px !important;
  font-weight: 600;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
}
.prd-table :deep(td.el-table__cell) {
  padding: 12px 0;
}
.prd-table :deep(.el-table__row--striped td.el-table__cell) {
  background: rgba(124, 58, 237, 0.02);
}
.prd-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

/* 优先级标签：11px / 圆角 5（HIGH 红、MEDIUM 琥珀、其余中性） */
.prd-pri {
  display: inline-block;
  padding: 0 8px;
  height: 22px;
  line-height: 20px;
  border-radius: 5px;
  font-size: 11px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.prd-pri.is-high,
.prd-pri.is-p0 {
  background: var(--ha-danger-bg);
  border-color: rgba(239, 68, 68, 0.3);
  color: var(--ha-danger-text);
}
.prd-pri.is-medium,
.prd-pri.is-p1 {
  background: var(--ha-warning-bg);
  border-color: rgba(245, 158, 11, 0.35);
  color: var(--ha-warning-text);
}
.prd-pri.is-low,
.prd-pri.is-p2,
.prd-pri.is-p3 {
  background: var(--ha-surface-hover);
  border-color: var(--ha-border-light);
  color: var(--ha-muted);
}
.prd-dep {
  display: inline-block;
  padding: 0 8px;
  height: 24px;
  line-height: 22px;
  border-radius: 5px;
  background: var(--ha-surface-hover);
  font-size: 11px;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
}
.prd-dash {
  color: var(--ha-muted);
}
.prd-table-hint {
  margin-top: 12px;
  font-size: 12px;
  line-height: 18px;
  color: var(--ha-muted);
}

/* ---- 底部：左侧汇总 + 右侧按钮（底色 #FCFCFD，上圆角） ---- */
.prd-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 32px;
  background: var(--ha-surface-hover);
  border-top: 1px solid var(--ha-border-light);
}
.prd-foot-sum {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--ha-ink-secondary);
}
.prd-foot-sum b {
  font-weight: 600;
  color: var(--ha-ink);
}
.prd-sep {
  width: 1px;
  height: 12px;
  background: var(--ha-border);
}
/* 高优先级红点（原型 7×6 圆点） */
.prd-high-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ha-danger);
}
.prd-foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ---- 行展开区 ---- */
.draft-detail { padding: 4px 12px 8px 48px; }
.dd-sec { margin-top: 8px; }
.dd-sec:first-child { margin-top: 0; }
.dd-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ha-muted);
  margin-bottom: 2px;
}
.dd-text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ha-ink-secondary, inherit);
  white-space: pre-wrap;
  word-break: break-word;
}
.dd-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.dd-unc {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ha-ink-secondary, inherit);
  word-break: break-word;
}
/* 规则编号：排障时可据此在日志/文档里定位判据出处 */
.dd-rule {
  flex: none;
  font-family: var(--ha-font-mono, monospace);
  font-size: 12px;
  color: var(--ha-muted);
}
/* 缺口影响面（「卡住 #2「xxx」」）：可能跨多条草案，允许换行，用 warning 色提示它会波及别处 */
.dd-block {
  font-size: 12px;
  color: var(--ha-warning-text, #B45309);
  word-break: break-word;
}
/* 行首草案序号：与 blockedBy 的编号同一套，等宽便于对照 */
.dd-seq {
  font-family: var(--ha-font-mono, monospace);
  color: var(--ha-muted);
}
/* 缺口引用标签（U1/U2）：与「验收前置未满足」里的 ref 对得上 */
.dd-ref {
  flex: none;
  font-family: var(--ha-font-mono, monospace);
  font-size: 12px;
  color: var(--ha-muted);
}
/* 说明性提示：解释「为什么这条要注意」，不参与状态色 */
.dd-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-muted);
}

/* ============================================================
   内层「修订草案」弹窗（对齐第二张设计稿）
   头部 padding 24/28/20/28，标题 17px，序号徽标 11px，副标题 14px
   ============================================================ */
:global(.draft-edit-dialog) {
  --el-dialog-border-radius: 18px !important;
  border: 1px solid var(--ha-border-light);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.14) !important;
  overflow: hidden;
}
:global(.draft-edit-dialog .el-dialog__header) {
  padding: 0 !important;
  margin: 0 !important;
  border-bottom: none !important;
}
/* 主体 pad 20/28（原型 → 弹窗主体） */
:global(.draft-edit-dialog .el-dialog__body) {
  padding: 20px 28px !important;
}
:global(.draft-edit-dialog .el-dialog__footer) {
  padding: 0 !important;
  border-top: none !important;
  background: transparent !important;
}

/* ---- 头部：pad 24/28/20/28，标题 17px，徽标 20 高 ---- */
.ped-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 24px 28px 20px;
}
.ped-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ped-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ha-ink);
}
/* 序号徽标：子任务 1/5（原型 h20 / r6 / pad 0-8 / 11px） */
.ped-seq {
  flex: none;
  padding: 0 8px;
  height: 20px;
  line-height: 20px;
  border-radius: 6px;
  background: var(--ha-surface-hover);
  font-size: 11px;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
}
/* 副标题：距标题 4px，14px（原型） */
.ped-sub {
  margin: 4px 0 0;
  font-size: 14px;
  line-height: 1.4;
  color: var(--ha-ink-secondary);
  word-break: break-word;
}

/* ---- 表单：字段行间距 20，标签列宽 88，标签 13px ---- */
.ped-form :deep(.el-form-item) {
  margin-bottom: 20px;
}
.ped-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}
.ped-form :deep(.el-form-item__label) {
  width: 88px;
  padding: 10px 0 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--ha-ink-secondary);
}
.ped-form :deep(.el-form-item__content) {
  line-height: 1.4;
}

/* ---- 输入类控件统一：高 40 / 圆角 9 / 1px 边框（原型 #E5E6EB） ---- */
.ped-form :deep(.el-input__wrapper),
.ped-form :deep(.el-select__wrapper) {
  min-height: 40px;
  padding: 0 12px;
  border-radius: 9px;
  background: var(--ha-surface);
  box-shadow: 0 0 0 1px var(--ha-border) inset;
}
.ped-form :deep(.el-input__wrapper.is-focus),
.ped-form :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--ha-primary) inset;
}
.ped-form :deep(.el-input__inner) {
  font-size: 13px;
  color: var(--ha-ink);
}
.ped-form :deep(.el-textarea__inner) {
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--ha-surface);
  box-shadow: 0 0 0 1px var(--ha-border) inset;
  font-size: 13px;
  line-height: 20px;
}
.ped-form :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--ha-primary) inset;
}
/* 字数统计：11px，贴右下（原型 pad-top 12） */
.ped-form :deep(.el-input__count) {
  right: 12px;
  bottom: 6px;
  padding-left: 4px;
  background: transparent;
  font-size: 11px;
  line-height: 16px;
  color: var(--ha-muted);
}

/* ---- 技能标签：选择框 42 高（装 28px 标签）；已选标签 28 高 / r7 / 紫底紫边 ---- */
.ped-form :deep(.ped-skill-select .el-select__wrapper) {
  min-height: 42px;
  padding: 6px 10px;
  gap: 8px;
}
.ped-form :deep(.el-select__selection) {
  gap: 8px;
}
.ped-form :deep(.el-select__selection .el-tag) {
  height: 28px;
  padding: 0 8px;
  border-radius: 7px;
  border: 1px solid rgba(124, 58, 237, 0.3);
  background: var(--ha-primary-light);
  color: var(--ha-primary-hover);
  font-size: 12px;
}
.ped-form :deep(.el-select__selection .el-tag .el-tag__close) {
  color: var(--ha-primary-hover);
  font-size: 12px;
}

/* 字段说明：距控件 8，11px（原型） */
.ped-field-hint {
  margin-top: 8px;
  font-size: 11px;
  line-height: 18px;
  color: var(--ha-muted);
}

/* ---- 说明区（判据 / 假设）：r9 / 1px #EEF0F4 / bg #F8F9FB / pad 10-12 ---- */
.ped-tip {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--ha-surface-hover);
  border: 1px solid var(--ha-border-light);
}
.ped-tip.is-block {
  margin-top: 12px;
}
.ped-tip-line {
  display: flex;
  gap: 6px;
  font-size: 11px;
  line-height: 18px;
  color: var(--ha-muted);
}
.ped-tip-line + .ped-tip-line {
  margin-top: 4px;
}
/* 前缀「谁来判、怎么判：」按内容宽度，不占固定列（原型 fit_content + gap 6） */
.ped-tip-key {
  flex: none;
  color: var(--ha-primary-hover);
  font-weight: 500;
  white-space: nowrap;
}

/* ---- 不确定性行：类型框 104 / 描述框自适应 / 删除 inline ---- */
.ped-unc-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.ped-unc-row :deep(.el-select) {
  flex: none;
  width: 104px;
}
/* 类型选项按语义着色（原型：待确认蓝 rgba(46,124,246,1) / 待检索橙 rgba(212,107,8,1)）；
   假设与待推导原型未给样本，沿用平台紫 / 弱化灰，不臆造新色 */
.ped-unc-row :deep(.el-select.is-unconfirmed .el-select__selected-item),
.ped-unc-row :deep(.el-select.is-unconfirmed .el-select__placeholder) {
  color: var(--ha-info);
}
.ped-unc-row :deep(.el-select.is-to_look_up .el-select__selected-item),
.ped-unc-row :deep(.el-select.is-to_look_up .el-select__placeholder) {
  color: var(--ha-warning-text);
}
.ped-unc-row :deep(.el-select.is-assumption .el-select__selected-item),
.ped-unc-row :deep(.el-select.is-assumption .el-select__placeholder) {
  color: var(--ha-primary);
}
.ped-unc-row :deep(.el-select.is-to_derive .el-select__selected-item),
.ped-unc-row :deep(.el-select.is-to_derive .el-select__placeholder) {
  color: var(--ha-muted);
}
.ped-unc-del {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 40px;
  padding: 0 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ha-danger);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
.ped-unc-del .el-icon {
  font-size: 16px;
}
.ped-unc-del:hover {
  opacity: 0.75;
}
/* 添加一项：118×36 / r9 / 1px（原型） */
.ped-add-unc {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 118px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--ha-border);
  border-radius: 9px;
  background: transparent;
  color: var(--ha-ink-secondary);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
.ped-add-unc .el-icon {
  font-size: 14px;
}
.ped-add-unc:hover {
  border-color: var(--ha-primary);
  color: var(--ha-primary);
}

/* ---- 优先级（只读）：h36 / r9 / pad 0-14 / gap 6 + 8×8 状态点 + 依赖说明 ---- */
.ped-pri-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-bottom: 8px;
}
.ped-pri {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--ha-border);
  border-radius: 9px;
  font-size: 13px;
  line-height: 1;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
  cursor: default;
}
.ped-pri-dot {
  flex: none;
  width: 7px;
  height: 6px;
  border-radius: 4px;
  background: var(--ha-muted);
}
/* 当前档位：红底红边红字（原型 #FFF1F0 / #FFCCC7 / #F5222D） */
.ped-pri.is-on {
  border-color: rgba(239, 68, 68, 0.28);
  background: var(--ha-danger-bg);
  color: var(--ha-danger);
  font-weight: 500;
}
.ped-pri.is-on .ped-pri-dot {
  background: var(--ha-danger);
}
/* 档位色点（原型 HIGH 红 #F5222D / MED 橙 #FA8C16 / LOW 灰 #C9CDD4）；
   放在 .is-on 之后同权重覆盖，选中态也按档位着色 */
.ped-pri.is-high .ped-pri-dot {
  background: var(--ha-danger);
}
.ped-pri.is-medium .ped-pri-dot {
  background: var(--ha-warning);
}
.ped-pri.is-low .ped-pri-dot {
  background: var(--ha-muted);
}
.ped-pri.is-on.is-high {
  border-color: rgba(239, 68, 68, 0.28);
  background: var(--ha-danger-bg);
  color: var(--ha-danger);
}
.ped-pri.is-on.is-medium {
  border-color: rgba(245, 158, 11, 0.32);
  background: var(--ha-warning-bg);
  color: var(--ha-warning-text);
}
.ped-pri.is-on.is-low {
  border-color: var(--ha-border);
  background: var(--ha-surface-hover);
  color: var(--ha-ink-secondary);
}
.ped-dep {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ha-muted);
}
.ped-dep .el-icon {
  font-size: 13px;
}

/* ---- 风险提示条：r9 / 琥珀底 #FFF7E6 + 边 #FFE7BA / 11px ---- */
.ped-warn {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 20px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--ha-warning-bg);
  border: 1px solid rgba(245, 158, 11, 0.35);
  font-size: 11px;
  line-height: 19px;
  color: var(--ha-warning-text);
}
.ped-warn .el-icon {
  flex: none;
  margin-top: 2px;
  font-size: 15px;
}

/* ---- 底部：pad 16/28，提示 12px，按钮 40 高 / r10 / pad 0-24 ---- */
.ped-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 28px;
  background: var(--ha-surface);
  border-top: 1px solid var(--ha-border-light);
}
.ped-foot-hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ha-muted);
}
.ped-foot-hint .el-icon {
  font-size: 14px;
}
.ped-foot-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ped-foot-actions :deep(.el-button) {
  height: 40px;
  padding: 0 24px;
  border-radius: 10px;
  border-color: var(--ha-border);
  background: var(--ha-surface);
  color: var(--ha-ink-secondary);
  font-size: 14px;
}
.ped-foot-actions :deep(.el-button:hover) {
  border-color: var(--ha-primary);
  color: var(--ha-primary);
}
.ped-foot-actions :deep(.el-button--primary) {
  border-color: var(--ha-primary);
  background: var(--ha-primary);
  color: #fff;
}
.ped-foot-actions :deep(.el-button--primary:hover) {
  border-color: var(--ha-primary-hover);
  background: var(--ha-primary-hover);
  color: #fff;
}

</style>
