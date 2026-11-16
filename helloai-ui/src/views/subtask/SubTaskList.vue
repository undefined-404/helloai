<template>
  <div class="page list-page ha-entrance-up">
    <!-- 页头：与任务管理 / 审查中心等同构（design-system.css → List Page Template） -->
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><List /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            子任务列表
            <span
              v-if="total"
              class="query-stats"
            >共 {{ total }} 条</span>
          </h2>
          <p class="page-subheading">
            按任务与状态筛选，跟进执行、核验与返工
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <!-- 按主任务过滤时提供列表/依赖图双视图切换 -->
        <el-radio-group
          v-if="taskId"
          v-model="viewMode"
          size="small"
        >
          <el-radio-button value="list">
            列表
          </el-radio-button>
          <el-radio-button value="dag">
            依赖图
          </el-radio-button>
          <el-radio-button value="iter">
            执行迭代
          </el-radio-button>
        </el-radio-group>
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          class="filter-select"
          @change="load(1)"
        >
          <el-option
            v-for="[k,v] in Object.entries(SUB_TASK_STATUS_MAP)"
            :key="k"
            :label="v.label"
            :value="k"
          />
        </el-select>
        <!-- 死信待处理入口（2026-10-18）：原「死信池」独立菜单已隐藏，
             改由本页按钮承载——同一页面不再出现两个入口，且数量角标比菜单位置更能提示 -->
        <el-badge
          :value="deadLetterCount"
          :hidden="deadLetterCount === 0"
          type="warning"
          class="deadletter-badge"
        >
          <el-button
            size="small"
            :type="statusFilter === 'DEAD_LETTER' ? 'warning' : 'default'"
            @click="toggleDeadLetterFilter"
          >
            死信待处理
          </el-button>
        </el-badge>
        <el-button
          size="small"
          @click="load(currentPage)"
        >
          刷新
        </el-button>
        <el-button
          v-auth="'subtask:add'"
          size="small"
          type="primary"
          @click="dispatchVisible = true"
        >
          快速派发
        </el-button>
      </div>
    </div>
    <el-card class="list-card">
      <!-- 主任务信息条：从任务管理页携带 taskId 跳转时展示归属主任务，并可清除筛选 -->
      <el-alert
        v-if="taskId"
        type="info"
        :closable="false"
        class="parent-task-bar"
      >
        <template #title>
          <span class="parent-task-title">
            当前主任务：<span
              class="link-cell"
              :title="parentTask?.title || ('跳转到主任务 ' + taskId)"
              @click="goParentTaskById(taskId)"
            >{{ parentTask?.title || taskId }}</span>
            <el-tag
              v-if="parentTask"
              :type="parentTask.status === 'DONE' ? 'success' : 'warning'"
              size="small"
              style="margin-left:8px"
            >
              {{ parentTask.status }}
            </el-tag>
          </span>
          <!-- 上钻：当前主任务是别的子任务二次拆分出来的 ⇒ 给一条回到来源子任务的路。
               原来是 el-button link（裸文字，没有可点暗示），按设计规范升级为浅紫胶囊：
               胶囊的底色/描边与行内下钻同一套，让人认出「这两个是同类动作」。 -->
          <span class="parent-task-actions">
            <DrillHandle
              v-if="parentTask?.parentSubTaskId"
              direction="back"
              :size="24"
              label="返回来源子任务"
              title="上钻：返回二次拆分的来源子任务"
              @click="goParentSubTask"
            />
            <el-button
              link
              type="primary"
              @click="clearTaskFilter"
            >
              查看全部子任务
            </el-button>
          </span>
        </template>
      </el-alert>
      <!-- 依赖图视图：拓扑分层流水线（同批可并行），点击节点跳详情 -->
      <SubTaskDagView
        v-if="taskId && viewMode === 'dag'"
        v-loading="fullListLoading"
        :sub-tasks="fullList"
        @node-click="goDetail"
      />
      <TaskIterationView
        v-if="taskId && viewMode === 'iter'"
        :items="iterations"
        :loading="iterLoading"
        :backfilling="backfilling"
        @backfill="handleBackfillIterations"
      />
      <template v-else>
        <el-table
          v-loading="loading"
          :data="displayList"
          border
          stripe
          style="width:100%"
        >
          <el-table-column
            label="标题"
            min-width="190"
          >
            <template #default="{ row }">
              <div class="cell-title">
                <!-- 拓扑序号小徽标：与依赖列/依赖图 #序号 同口径，仅按主任务过滤时展示 -->
                <span
                  v-if="taskId && seqMap.get(String(row.id))"
                  class="seq-badge"
                >#{{ seqMap.get(String(row.id)) }}</span>
                <span
                  class="link-cell cell-title-text"
                  :title="row.title"
                  @click="router.push('/sub-tasks/' + row.id)"
                >{{ row.title }}</span>
                <!-- 下钻：该子任务已二次拆分出独立主任务（ADR-008）⇒ 标题后给入口，
                     落到「任务管理」里那个派生主任务本身（不是它的子任务列表——那要再点一次标题）。
                     用 24px 浅紫徽标而非裸图标：表格是 stripe 斑马纹，透明底图标在白色行上
                     会退化成正文的一部分，看不出可点。 -->
                <DrillHandle
                  v-if="derivedTaskIdOf(row)"
                  direction="down"
                  :size="24"
                  title="下钻：查看二次拆分出的主任务"
                  @click.stop="goDerivedTask(row)"
                />
              </div>
            </template>
          </el-table-column>
          <!-- 未按主任务过滤时展示归属任务，避免与顶部信息条重复 -->
          <el-table-column
            v-if="!taskId"
            label="所属任务"
            min-width="140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span
                class="link-cell"
                @click="goParentTask(row)"
              >{{ row.taskTitle || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="170"
          >
            <template #default="{ row }">
              <div class="cell-tags">
                <el-tag
                  :type="getSubTaskStatusMeta(row.status)?.type || 'info'"
                  size="small"
                >
                  {{ getSubTaskStatusMeta(row.status)?.label || row.status }}
                </el-tag>
                <!-- 无候选等待可视化（短版）：PENDING 且 context.noCandidate.nextDispatchAt 存在时，
                     在状态标签旁补一个「约 Ns 后重试」小标签；已转人工则显示「等待人工介入」 -->
                <el-tag
                  v-if="pendingWaitOf(row)"
                  size="small"
                  :type="pendingWaitOf(row)!.kind === 'manual' ? 'warning' : 'info'"
                  class="wait-tag"
                >
                  {{ pendingWaitOf(row)!.text }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <!-- 按主任务过滤时展示前置依赖（可点击跳依赖项详情），序号与依赖图/草案审阅一致 -->
          <el-table-column
            v-if="taskId"
            label="依赖"
            min-width="120"
          >
            <template #default="{ row }">
              <template v-if="depItems(row).length">
                <el-tag
                  v-for="dep in depItems(row)"
                  :key="dep.id"
                  size="small"
                  class="dep-tag"
                  :title="dep.title"
                  @click="goDetail(dep.id)"
                >
                  #{{ dep.seq }}
                </el-tag>
              </template>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column
            label="评分"
            width="84"
          >
            <template #default="{ row }">
              <el-tooltip
                v-if="row.scoreGrade"
                :content="scoreTooltip(row)"
                placement="top"
              >
                <el-tag
                  :type="SCORE_GRADE_MAP[row.scoreGrade]?.type || 'info'"
                  size="small"
                >
                  {{ SCORE_GRADE_MAP[row.scoreGrade]?.label || row.scoreGrade }}
                </el-tag>
              </el-tooltip>
              <!-- 空值不是「没算出来」而是「还没到算的时机」：评分只在 DONE 时写入，
                   不说明会让人以为功能坏了（2026-10-18 用户反馈） -->
              <el-tooltip
                v-else
                content="评分在子任务完成（DONE）时自动计算；进行中/已取消/未指派执行者时为空属预期"
                placement="top"
              >
                <span class="score-empty">-</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column
            label="负责人"
            min-width="130"
          >
            <template #default="{ row }">
              <span
                class="agent-cell"
                :title="row.assignedAgentName || ''"
              >{{ row.assignedAgentName || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="创建时间"
            width="132"
          >
            <template #default="{ row }">
              <div class="time-cell">
                <span>{{ timeParts(row.createTime).date }}</span>
                <span class="time-sub">{{ timeParts(row.createTime).time }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="150"
            fixed="right"
          >
            <template #default="{ row }">
              <div class="action-cell">
                <!-- 主操作：详情保持外置（参考任务管理主按钮模式） -->
                <el-button
                  size="small"
                  @click="router.push('/sub-tasks/' + row.id)"
                >
                  详情
                </el-button>
                <!-- 次要操作：统一收进更多下拉（参考任务管理；无操作的状态只展示详情） -->
                <el-dropdown
                  v-if="hasMoreActions(row)"
                  trigger="click"
                  @command="(cmd: string) => handleCommand(cmd, row)"
                >
                  <el-button
                    size="small"
                    :aria-label="`子任务「${row.title}」更多操作`"
                  >
                    更多
                    <el-icon class="el-icon--right">
                      <ArrowDown />
                    </el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <!-- 注意：下拉项不能用 v-auth。ElDropdownItem 根节点是 el-roving-focus-item 的
                           slot（fragment，非单一元素），Vue 会忽略其上的自定义指令，v-auth 静默失效。
                           故一律用 v-if + hasPermission 过滤。 -->
                      <el-dropdown-item
                        v-if="row.status==='PENDING' && auth.hasPermission('subtask:claim')"
                        command="claim"
                      >
                        认领
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="row.status==='IN_PROGRESS' && auth.hasPermission('subtask:pause')"
                        command="pause"
                      >
                        暂停
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="row.status==='PAUSED' && auth.hasPermission('subtask:resume')"
                        command="resume"
                      >
                        恢复
                      </el-dropdown-item>
                      <!-- 暂停后换人：PAUSED 是人工处置窗口（恢复/换人二选一），后端先自动恢复再标 BLOCKED 进重调度链 -->
                      <el-dropdown-item
                        v-if="row.status==='PAUSED' && auth.hasPermission('subtask:reassign')"
                        command="reassign"
                      >
                        <span class="dropdown-danger">换人</span>
                      </el-dropdown-item>
                      <!-- V25 死信人工兜底：重新指派给指定 Agent（DEAD_LETTER → ASSIGNED） -->
                      <el-dropdown-item
                        v-if="row.status==='DEAD_LETTER' && auth.hasPermission('subtask:redispatch')"
                        command="redispatch"
                      >
                        <span class="dropdown-danger">重新指派</span>
                      </el-dropdown-item>
                      <!-- 已指派未开工（ASSIGNED）：首个分配不当（外部队列 Agent 不在线等）时的人工换人入口。
                           此前三个改派入口均不收该状态，人工无法介入，只能等 dispatch.assigned-timeout-minutes（默认 10 分钟）自动回收 -->
                      <el-dropdown-item
                        v-if="row.status==='ASSIGNED' && auth.hasPermission('subtask:reassign')"
                        command="reassign"
                      >
                        <span class="dropdown-danger">换人</span>
                      </el-dropdown-item>
                      <!-- BLOCKED 阻塞子任务：重新调度（reset → PENDING 后交调度链） -->
                      <el-dropdown-item
                        v-if="row.status==='BLOCKED' && auth.hasPermission('subtask:reassign')"
                        command="reassign"
                      >
                        重新调度
                      </el-dropdown-item>
                      <!-- ADR-008 §12 执行期重构：自动链路已停的四态 → 人工接管 -->
                      <el-dropdown-item
                        v-if="RESTRUCTURE_ENTRY_STATUSES.includes(row.status) && auth.hasPermission('subtask:restructure')"
                        command="restructure"
                        divided
                      >
                        进入重构
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="row.status==='RESTRUCTURING' && auth.hasPermission('subtask:restructure')"
                        command="restructureEdit"
                      >
                        编辑要求
                      </el-dropdown-item>
                      <el-dropdown-item
                        v-if="row.status==='RESTRUCTURING' && auth.hasPermission('subtask:restructure')"
                        command="restructureApply"
                      >
                        整改完成回派发
                      </el-dropdown-item>
                      <!-- AWAITING_DERIVED 不是死状态：派生任务被删/作废后，后端按事实
                           判定并自愈回 RESTRUCTURING，用户从这里重新拆出去 -->
                      <el-dropdown-item
                        v-if="row.status==='AWAITING_DERIVED' && auth.hasPermission('subtask:derive')"
                        command="rederive"
                        divided
                      >
                        重新拆出去
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!list.length && !loading"
          description="暂无子任务"
        />
        <el-pagination
          v-if="total > 0"
          class="list-pager"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="loadPage"
          @size-change="onSizeChange"
        />
      </template>

      <!-- 认领弹窗 -->
      <el-dialog
        v-model="claimDialog.visible"
        title="认领子任务"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ claimDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="认领 Agent">
            <AgentSelect
              v-model="claimDialog.agentId"
              placeholder="选择认领的 Agent"
              executor-only
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="claimDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="claimDialog.loading"
            :disabled="!claimDialog.agentId"
            @click="doClaim"
          >
            确认认领
          </el-button>
        </template>
      </el-dialog>

      <!-- 死信重新指派弹窗：复用 AgentSelect 选目标 Agent -->
      <el-dialog
        v-model="redispatchDialog.visible"
        title="死信重新指派"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="子任务">
            <span>{{ redispatchDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="目标 Agent">
            <AgentSelect
              v-model="redispatchDialog.agentId"
              placeholder="选择重新指派的 Agent"
              executor-only
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="redispatchDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="redispatchDialog.loading"
            :disabled="!redispatchDialog.agentId"
            @click="doRedispatch"
          >
            确认指派
          </el-button>
        </template>
      </el-dialog>

      <!-- BLOCKED 重新调度 / PAUSED、ASSIGNED 换人 共用弹窗：选目标 Agent，后端先标 BLOCKED 再重新进入调度链 -->
      <el-dialog
        v-model="reassignDialog.visible"
        :title="reassignIsHandover ? '改派任务执行者' : '重新调度阻塞子任务'"
        width="420px"
        top="5vh"
        append-to-body
      >
        <el-form label-width="100px">
          <el-alert
            v-if="reassignDialog.row?.status === 'PAUSED'"
            type="warning"
            :closable="false"
            show-icon
            title="将为该暂停任务改派新执行者"
            description="系统会将任务标记为阻塞后重新分配给新 Agent；原执行者将收到任务已转移通知。"
            style="margin-bottom:12px"
          />
          <el-alert
            v-else-if="reassignDialog.row?.status === 'ASSIGNED'"
            type="warning"
            :closable="false"
            show-icon
            title="将为该已指派任务改派新执行者"
            description="任务已指派但尚未开工（原 Agent 未认领/未执行），可在此直接改派；系统会先标记为阻塞再重新分配给新 Agent。"
            style="margin-bottom:12px"
          />
          <el-form-item label="子任务">
            <span>{{ reassignDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item label="目标 Agent">
            <AgentSelect
              v-model="reassignDialog.agentId"
              :placeholder="reassignIsHandover ? '选择新的执行 Agent' : '选择重新调度的 Agent'"
              executor-only
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reassignDialog.visible = false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="reassignDialog.loading"
            :disabled="!reassignDialog.agentId"
            @click="doReassign"
          >
            {{ reassignIsHandover ? '确认改派' : '确认重新调度' }}
          </el-button>
        </template>
      </el-dialog>

      <!-- ADR-008 §12 执行期重构：进入重构（只填原因）/ 重构中编辑四要素并回派发 -->
      <el-dialog
        v-model="restructureDialog.visible"
        :title="restructureDialog.editing ? '重构要求' : '进入人工重构'"
        width="640px"
        top="6vh"
        append-to-body
      >
        <el-alert
          v-if="!restructureDialog.editing"
          type="warning"
          :closable="false"
          show-icon
          title="进入重构后该子任务不再参与自动调度"
          description="可修改要求后重新回派发；回派发前会做一次 G5 契约校验（下游引用了整改前产出标识时会被拒绝）。"
          style="margin-bottom:12px"
        />
        <el-form label-width="96px">
          <el-form-item
            v-if="!restructureDialog.editing"
            label="子任务"
          >
            <span>{{ restructureDialog.row?.title || '-' }}</span>
          </el-form-item>
          <el-form-item
            v-if="!restructureDialog.editing"
            label="原因"
          >
            <el-input
              v-model="restructureDialog.reason"
              maxlength="200"
              placeholder="可选：为什么需要重构"
            />
          </el-form-item>
          <template v-else>
            <el-form-item label="标题">
              <el-input
                v-model="restructureDialog.form.title"
                maxlength="200"
              />
            </el-form-item>
            <el-form-item label="要求内容">
              <el-input
                v-model="restructureDialog.form.content"
                type="textarea"
                :rows="5"
              />
            </el-form-item>
            <el-form-item label="交付物">
              <el-input
                v-model="restructureDialog.form.deliverable"
                type="textarea"
                :rows="3"
              />
            </el-form-item>
            <el-form-item label="验收标准">
              <el-input
                v-model="restructureDialog.form.acceptance"
                type="textarea"
                :rows="4"
              />
            </el-form-item>
            <el-form-item label="约束">
              <el-input
                v-model="restructureDialog.form.constraints"
                type="textarea"
                :rows="2"
              />
            </el-form-item>
            <el-form-item label="验收判据">
              <el-select
                v-model="restructureDialog.form.acceptanceKind"
                style="width:220px"
              >
                <el-option
                  label="自动核验 AUTOMATED"
                  value="AUTOMATED"
                />
                <el-option
                  label="人工评估 REVIEWED"
                  value="REVIEWED"
                />
                <el-option
                  label="结构检查 STRUCTURAL"
                  value="STRUCTURAL"
                />
              </el-select>
            </el-form-item>
          </template>
        </el-form>
        <template #footer>
          <el-button @click="restructureDialog.visible = false">
            取消
          </el-button>
          <el-button
            v-if="!restructureDialog.editing"
            type="primary"
            :loading="restructureDialog.loading"
            @click="doEnterRestructure"
          >
            进入重构
          </el-button>
          <template v-else>
            <el-button
              v-if="auth.hasPermission('subtask:derive')"
              type="warning"
              :loading="restructureDialog.loading"
              @click="doDeriveRestructureSession"
            >
              拆出去当主任务
            </el-button>
            <el-button
              :loading="restructureDialog.loading"
              @click="doSaveRestructure"
            >
              保存
            </el-button>
            <el-button
              type="primary"
              :loading="restructureDialog.loading"
              @click="doApplyRestructure"
            >
              保存并回派发
            </el-button>
          </template>
        </template>
      </el-dialog>

      <!-- M4.5: 快速派发对话框 -->
      <QuickDispatchDialog
        v-model="dispatchVisible"
        @done="load(1)"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, List } from '@element-plus/icons-vue'
import { subTaskApi } from '@/api/subTask'
import { taskApi } from '@/api/task'
import AgentSelect from '@/components/AgentSelect.vue'
import DrillHandle from '@/components/DrillHandle.vue'
import QuickDispatchDialog from '@/components/QuickDispatchDialog.vue'
import SubTaskDagView from '@/components/SubTaskDagView.vue'
import TaskIterationView from '@/components/TaskIterationView.vue'
import { SUB_TASK_STATUS_MAP, SCORE_GRADE_MAP, type AcceptanceKind } from '@/types'
import { fmtTime, splitDateTime } from '@/utils/tableConfig'
import { orderByDependency } from '@/utils/subTaskDag'
import { queryString } from '@/utils/queryParam'
import { useAutoRefresh } from '@/composables/useAutoRefresh'
import { resolvePendingWait, usePendingCountdown, type PendingWait } from '@/composables/usePendingCountdown'
import { useAuthStore } from '@/stores/auth'
import type { Task, SubTask, SubTaskStatus, TaskIteration } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const list = ref<SubTask[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const statusFilter = ref<SubTaskStatus | ''>('')
const dispatchVisible = ref(false)
// 路由 query 中的主任务 ID（LongId 保持 string，不转 Number 防精度丢）
const taskId = computed(() => queryString(route.query, 'taskId') || '')
// 路由 query 中的状态筛选（池菜单跳 /sub-tasks?status=DEAD_LETTER 复用本页）
const statusQuery = computed(() => queryString(route.query, 'status') || '')
const parentTask = ref<Task | null>(null)
// 列表/依赖图/执行迭代三视图（仅按主任务过滤时可切换）
const viewMode = ref<'list' | 'dag' | 'iter'>('list')
// 全量子任务（依赖图渲染 + 依赖列序号映射用；分页列表里依赖项可能不在当前页）
const fullList = ref<SubTask[]>([])
const fullListLoading = ref(false)
// V42 迭代记录
const iterations = ref<TaskIteration[]>([])
const iterLoading = ref(false)
const backfilling = ref(false)

/** 时间拆成日期/时间两行（创建时间列窄列展示） */
function timeParts(t: string | null | undefined) {
  return splitDateTime(t) ?? { date: '-', time: '' }
}

async function loadFullList() {
  if (!taskId.value) { fullList.value = []; return }
  fullListLoading.value = true
  try {
    fullList.value = await subTaskApi.listAllByTask(taskId.value)
  } catch {
    // 全量拉取失败不阻断分页列表，依赖列/依赖图降级为空
    fullList.value = []
  } finally { fullListLoading.value = false }
}

async function loadIterations() {
  if (!taskId.value) { iterations.value = []; return }
  iterLoading.value = true
  try {
    iterations.value = await taskApi.findTaskIterationsByTaskId(taskId.value)
  } catch {
    iterations.value = []
  } finally { iterLoading.value = false }
}

async function handleBackfillIterations() {
  backfilling.value = true
  try {
    const res = await taskApi.backfillTaskIterations()
    ElMessage.success('回填完成，共 ' + res.backfilledCount + ' 个任务')
    await loadIterations()
  } catch {
    ElMessage.error('回填失败')
  } finally { backfilling.value = false }
}

// 拓扑正序全局序号映射（与依赖图、草案审阅弹窗的 #序号 口径一致）
const seqMap = computed(() => {
  const map = new Map<string, number>()
  orderByDependency(fullList.value).forEach((s, i) => map.set(String(s.id), i + 1))
  return map
})

// 展示列表：按主任务过滤时按拓扑序号 #1→#n 正序排列（seqMap 未就绪时保持原序），全局列表维持后端顺序
const displayList = computed(() => {
  if (!taskId.value) return list.value
  const sm = seqMap.value
  return [...list.value].sort(
    (a, b) => (sm.get(String(a.id)) ?? Infinity) - (sm.get(String(b.id)) ?? Infinity)
  )
})

// 行的前置依赖展示项：#序号 + 标题（悬浮提示），点击跳依赖项详情
function depItems(row: SubTask): { id: string; seq: number; title: string }[] {
  return (row.dependsOn || []).map(String)
    .filter(d => seqMap.value.has(d))
    .map(d => ({
      id: d,
      seq: seqMap.value.get(d)!,
      title: fullList.value.find(s => String(s.id) === d)?.title || ''
    }))
    .sort((a, b) => a.seq - b.seq)
}

function goDetail(id: string) { router.push('/sub-tasks/' + id) }

// ── 无候选等待倒计时（短版）：与详情页共用 usePendingCountdown 口径 ──
// PENDING 且 context.noCandidate.nextDispatchAt 存在的行，在状态标签旁显示「约 Ns 后重试」；
// 单页最多数十行，逐行按 nowTick 计算即可，无需为每行单独建定时器。
// 全列表共用 1 个每秒 tick：仅当当前页存在可见倒计时才启动，避免空表也空转。
const pendingWaitOf = (row: SubTask): PendingWait | null =>
  resolvePendingWait(row.status, row.context, now.value, true)

const hasCountdown = computed(() =>
  displayList.value.some(row => pendingWaitOf(row)?.kind === 'countdown')
)
const { now } = usePendingCountdown({ active: hasCountdown })

async function loadParentTask() {
  if (!taskId.value) { parentTask.value = null; return }
  try {
    parentTask.value = await taskApi.getById(taskId.value)
  } catch {
    // 主任务查询失败不阻断子任务列表，信息条降级显示 taskId
    parentTask.value = null
  }
}

async function load(page = 1) {
  loading.value = true
  try {
    const params: { taskId?: string; status?: string; page: number; pageSize: number } = { page, pageSize: pageSize.value }
    if (taskId.value) params.taskId = taskId.value
    if (statusFilter.value) params.status = statusFilter.value
    // 后端真分页：传 page 返回 PageResult（list/total）
    const res = await subTaskApi.list(params)
    list.value = res.list
    // 防御性 Number 归一化：后端已修（JacksonConfig 按 ID 语义收窄，PageResult.total
    // 恢复为 JSON 数字），此处仅兜底滚动升级期间的契约错位。
    // el-pagination 要求 total 为 Number，否则整个分页组件 DOM 不挂载。
    total.value = Number(res.total) || 0
    currentPage.value = page
    // 按主任务过滤时同步刷新全量列表（依赖列序号/依赖图状态色保持最新）
    if (taskId.value) { loadFullList(); loadIterations() }
  } finally { loading.value = false }
}

/**
 * 死信待处理数量（原「死信池」独立菜单已隐藏，改由本页按钮承载）。
 *
 * <p>用 size=1 的分页 total 取数量：后端 SQL 只查 COUNT + LIMIT 1，代价可忽略；
 * 失败静默为 0（角标缺失不影响列表主流程，与 eventCatalog 的降级口径一致）。</p>
 */
const deadLetterCount = ref(0)
async function loadDeadLetterCount() {
  try {
    const res = await subTaskApi.list({ status: 'DEAD_LETTER', page: 1, pageSize: 1 })
    deadLetterCount.value = Number(res.total) || 0
  } catch {
    deadLetterCount.value = 0
  }
}

// 任何来源的筛选变化（下拉/按钮/路由 query）都刷新角标，保证数量与列表口径一致
watch(statusFilter, () => { loadDeadLetterCount() })

/** 切到死信筛选（等价于原「死信池」菜单入口）；再次点击可清除筛选回到全部。 */
function toggleDeadLetterFilter() {
  statusFilter.value = statusFilter.value === 'DEAD_LETTER' ? '' : 'DEAD_LETTER'
  load(1)
}
function loadPage(page: number) { load(page) }

/** 评分悬浮说明：等级 + 复合分 + 五维明细（有则显示，缺项省略）。 */
function scoreTooltip(row: SubTask): string {
  const parts: string[] = []
  if (row.compositeScore != null) parts.push('综合 ' + row.compositeScore + ' 分')
  const f = (row as any).scoreFactors as Record<string, number> | undefined
  if (f) {
    const dims = [
      ['时间', f.timeScore], ['质量', f.qualityScore], ['协作', f.coopScore],
      ['稳定', f.stabilityScore], ['效率', f.efficiencyScore],
    ].filter(([, v]) => v != null).map(([k, v]) => `${k} ${v}`)
    if (dims.length) parts.push(dims.join(' · '))
  }
  return parts.length ? parts.join('\n') : '评分在子任务完成时自动计算'
}

// 切换每页条数：回到第一页避免新 pageSize 下越界
function onSizeChange(s: number) {
  pageSize.value = s
  currentPage.value = 1
  load(1)
}

function clearTaskFilter() { router.replace('/sub-tasks') }

// 顶部信息条主任务名称点击 → 跳转任务管理页并筛选该主任务
function goParentTaskById(id: string) {
  router.push('/tasks?taskId=' + id)
}

// 所属任务列点击 → 跳转任务管理页并筛选该主任务
function goParentTask(row: SubTask) {
  if (row.taskId) router.push('/tasks?taskId=' + row.taskId)
}

// ── 二次拆分下钻（ADR-008）──
// 指针来源：子任务物化时写进 sub_task.context.derivedTaskId；列表接口已带 context，
// 无需为每行再查一次派生关系（避免 20 行 20 次请求的 N+1）。
function derivedTaskIdOf(row: SubTask): string {
  const v = row.context?.derivedTaskId
  return v != null && String(v) !== '' ? String(v) : ''
}

/**
 * 下钻：该子任务已二次拆分出独立主任务（ADR-008）⇒ 落到**那个主任务本身**。
 *
 * <p>落点是「任务管理」并按 taskId 筛选，因为派生出来的是一个独立任务（有自己的状态、
 * 进度、报告与操作入口），不是当前列表里的兄弟子任务。曾经跳 `/sub-tasks?taskId=`——
 * 那会直接进「派生主任务的子任务列表」，跳过了主任务本体，用户点下钻看不到它自己的状态。</p>
 */
function goDerivedTask(row: SubTask) {
  const id = derivedTaskIdOf(row)
  if (!id) return
  router.push('/tasks?taskId=' + id)
}

/** 上钻：当前主任务是某个子任务派生出来的 ⇒ 回到那个子任务详情。 */
function goParentSubTask() {
  const id = parentTask.value?.parentSubTaskId
  if (id == null || String(id) === '') return
  router.push('/sub-tasks/' + String(id))
}

// 同页面内 taskId 变化（如清除筛选 / 从不同主任务进入）时联动刷新
watch(taskId, () => {
  if (!taskId.value) { viewMode.value = 'list'; fullList.value = []; iterations.value = [] }
  loadParentTask()
  load()
  loadDeadLetterCount()
})
// 同页面内 status query 变化（子任务菜单 ↔ 死信池菜单切换）时同步筛选并刷新
watch(statusQuery, (v) => {
  // 防御：仅在合法 SubTaskStatus 范围内赋值，避免后端不识别枚举值
  const validStatuses: SubTaskStatus[] = [
    'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW',
    'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER',
    'AWAITING_DERIVED', 'RESTRUCTURING'
  ]
  statusFilter.value = (validStatuses as string[]).includes(v) ? (v as SubTaskStatus) : ''
  load(1)
})

const claimDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

async function handleClaim(row: SubTask) {
  claimDialog.row = row
  claimDialog.agentId = null
  claimDialog.visible = true
}

async function doClaim() {
  if (!claimDialog.row || !claimDialog.agentId) return
  claimDialog.loading = true
  try {
    await subTaskApi.claim(claimDialog.row.id, claimDialog.agentId)
    ElMessage.success('认领成功')
    claimDialog.visible = false
    load()
  } finally { claimDialog.loading = false }
}

async function handlePause(row: SubTask) {
  try {
    await ElMessageBox.confirm(`确定暂停子任务「${row.title}」？`, '确认暂停', { type: 'warning' })
    await subTaskApi.pause(row.id)
    ElMessage.success('已暂停')
    load()
  } catch {}
}

async function handleResume(row: SubTask) {
  try {
    await subTaskApi.resume(row.id)
    ElMessage.success('已恢复')
    load()
  } catch {}
}

// V25 死信人工兜底：重新指派（后端清熔断计数并转 ASSIGNED）
const redispatchDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

function handleRedispatch(row: SubTask) {
  redispatchDialog.row = row
  redispatchDialog.agentId = null
  redispatchDialog.visible = true
}

async function doRedispatch() {
  if (!redispatchDialog.row || !redispatchDialog.agentId) return
  redispatchDialog.loading = true
  try {
    await subTaskApi.redispatchDeadLetter(redispatchDialog.row.id, redispatchDialog.agentId)
    ElMessage.success('重新指派成功')
    redispatchDialog.visible = false
    load()
  } finally { redispatchDialog.loading = false }
}

// BLOCKED 重新调度 / PAUSED 换人 共用弹窗状态（后端先标 BLOCKED 再交弹性调度链，受熔断计数管控）
const reassignDialog = reactive<{ visible: boolean; loading: boolean; agentId: string | number | null; row: SubTask | null }>({
  visible: false, loading: false, agentId: null, row: null,
})

function handleReassign(row: SubTask) {
  reassignDialog.row = row
  reassignDialog.agentId = null
  reassignDialog.visible = true
}

// 弹窗语义分流：PAUSED（暂停后换人）/ ASSIGNED（已指派未开工）都走人工「改派」链，
// BLOCKED 才走「重新调度」。文案与按钮随之切换，避免把 ASSIGNED 说成「阻塞」。
const reassignIsHandover = computed(() =>
  reassignDialog.row?.status === 'PAUSED' || reassignDialog.row?.status === 'ASSIGNED')

async function doReassign() {
  if (!reassignDialog.row || !reassignDialog.agentId) return
  reassignDialog.loading = true
  try {
    // PAUSED（暂停后换人）/ ASSIGNED（已指派未开工，首个分配不当）同走人工换人链：
    // 后端先（PAUSED 时恢复）再标 BLOCKED 进入重调度链
    const result = reassignDialog.row.status === 'PAUSED' || reassignDialog.row.status === 'ASSIGNED'
      ? await subTaskApi.redispatchInProgress(reassignDialog.row.id, reassignDialog.agentId)
      : await subTaskApi.reassign(reassignDialog.row.id, reassignDialog.agentId)
    // 闸门（熔断 / 退避）拦截时后端返回 applied=false 且不改动任务状态，
    // 此处必须消费，否则前端仍报「成功」——用户感知就是「点了没反应」
    if (result && result.applied === false) {
      ElMessage.warning(result.reason === 'backoff'
        ? `重派被退避窗口拦截，最早可于 ${result.nextAllowed ? fmtTime(result.nextAllowed) : '稍后'} 重试`
        : '重派被熔断闸门拦截（重派预算耗尽），子任务已转死信池，请走「重新指派」')
      reassignDialog.visible = false
      load()
      return
    }
    ElMessage.success(reassignDialog.row.status === 'BLOCKED'
      ? '已重新调度，子任务重新进入分发链'
      : '已改派，子任务重新进入分发链')
    reassignDialog.visible = false
    load()
  } finally { reassignDialog.loading = false }
}

// ── ADR-008 §12 执行期重构：入口 / 编辑四要素 / 回派发（G5 由后端拒） ──
const RESTRUCTURE_ENTRY_STATUSES: readonly SubTask['status'][] = ['BLOCKED', 'DEAD_LETTER', 'REWORK', 'PAUSED']
const restructureDialog = reactive({
  visible: false,
  loading: false,
  editing: false,
  reason: '',
  row: null as SubTask | null,
  form: {
    title: '',
    content: '',
    deliverable: '',
    acceptance: '',
    constraints: '',
    acceptanceKind: 'AUTOMATED' as AcceptanceKind,
  },
})

function handleRestructure(row: SubTask) {
  restructureDialog.row = row
  restructureDialog.editing = row.status === 'RESTRUCTURING'
  restructureDialog.reason = ''
  restructureDialog.form = {
    title: row.title || '',
    content: row.content || '',
    deliverable: row.deliverable || '',
    acceptance: row.acceptance || '',
    constraints: row.constraints || '',
    acceptanceKind: (row.acceptanceKind || 'AUTOMATED') as AcceptanceKind,
  }
  restructureDialog.visible = true
}

async function doEnterRestructure() {
  if (!restructureDialog.row) return
  restructureDialog.loading = true
  try {
    await subTaskApi.enterRestructuring(restructureDialog.row.id, restructureDialog.reason || undefined)
    ElMessage.success('已进入人工重构')
    restructureDialog.visible = false
    load()
  } finally {
    restructureDialog.loading = false
  }
}

async function saveRestructureForm() {
  if (!restructureDialog.row) return
  await subTaskApi.updateRestructure(restructureDialog.row.id, {
    title: restructureDialog.form.title,
    content: restructureDialog.form.content,
    deliverable: restructureDialog.form.deliverable,
    acceptance: restructureDialog.form.acceptance,
    constraints: restructureDialog.form.constraints,
    acceptanceKind: restructureDialog.form.acceptanceKind,
  })
}

async function doSaveRestructure() {
  restructureDialog.loading = true
  try {
    await saveRestructureForm()
    ElMessage.success('要求已保存')
    restructureDialog.visible = false
    load()
  } finally {
    restructureDialog.loading = false
  }
}

async function doApplyRestructure() {
  restructureDialog.loading = true
  try {
    // 先落编辑再回派发：G5 判定读的是库里当前值，不保存就等于没改
    await saveRestructureForm()
    await subTaskApi.applyRestructure(restructureDialog.row!.id)
    ElMessage.success('整改完成，已回到待派发')
    restructureDialog.visible = false
    load()
  } catch {
    // G5 拒绝 / 业务失败：全局拦截器已提示，保留弹窗让用户继续调整
  } finally {
    restructureDialog.loading = false
  }
}

// ADR-008 §13：拆出去当主任务 —— 先落当前编辑，再开派生会话（四段上下文已由后端装配）
async function doDeriveRestructureSession() {
  if (!restructureDialog.row) return
  restructureDialog.loading = true
  try {
    await saveRestructureForm()
    const conversationId = await subTaskApi.startRestructureSession(restructureDialog.row.id)
    restructureDialog.visible = false
    ElMessage.success('已创建重构会话，请在对话中讨论并确认终稿')
    router.push({ path: '/requirement-chat', query: { id: conversationId } })
  } finally {
    restructureDialog.loading = false
  }
}

async function handleApplyRestructure(row: SubTask) {
  try {
    await ElMessageBox.confirm(
      '整改完成并回到待派发？下游引用了整改前产出标识时会被 G5 拒绝。',
      '确认回派发',
      { type: 'warning' },
    )
    await subTaskApi.applyRestructure(row.id)
    ElMessage.success('已回到待派发')
    load()
  } catch {}
}

/**
 * AWAITING_DERIVED 状态下「重新拆出去」。
 *
 * <p>不在前端判「是否已拆分」——那是派生行的事实（`task.parent_sub_task_id` +
 * 状态非 CANCELLED + 未删），只有后端查得准。直接调接口，由后端分支：
 * 有活跃派生 ⇒ 400 并告知派生任务 id；无 ⇒ 自愈回 RESTRUCTURING 后建会话。</p>
 */
async function handleReDerive(row: SubTask) {
  try {
    const conversationId = await subTaskApi.startRestructureSession(row.id)
    ElMessage.success('已重新进入拆分会话，请在对话中确认终稿')
    router.push({ path: '/requirement-chat', query: { id: conversationId } })
  } catch {
    // 400 的具体原因（已完成拆分 / 状态不允许）由请求层统一提示，这里只刷新状态：
    // 自愈会改状态，列表需要显示最新的 RESTRUCTURING。
    load()
  }
}

// ── 更多下拉：状态操作统一分派（认领/暂停/恢复/换人/重新指派/重新调度/重构） ──
// 仅当当前状态存在可执行操作时展示「更多」（无操作状态只留「详情」）；
// 下拉内每一项都是写动作（模板里各自 v-auth 门控），若角色一个动作码都没有
// （如 GUEST），再展示「更多」只会得到一个点开为空的下拉，故一并按权限收口。
// AWAITING_DERIVED 也列进来：它不是死状态——派生任务被删除或作废后，后端会按
// 「是否还有活跃派生」这个事实判定并自愈回 RESTRUCTURING，用户需要从这里重新拆出去。
const MORE_ACTION_STATUSES: readonly SubTask['status'][] = ['PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'BLOCKED', 'DEAD_LETTER', 'REWORK', 'RESTRUCTURING', 'AWAITING_DERIVED']
const MORE_ACTION_CODES = ['subtask:claim', 'subtask:pause', 'subtask:resume', 'subtask:reassign', 'subtask:redispatch', 'subtask:status', 'subtask:edit', 'subtask:restructure', 'subtask:derive'] as const
function hasMoreActions(row: SubTask) {
  if (!MORE_ACTION_STATUSES.includes(row.status)) return false
  return MORE_ACTION_CODES.some(code => auth.hasPermission(code))
}

function handleCommand(command: string, row: SubTask) {
  if (command === 'claim') handleClaim(row)
  else if (command === 'pause') handlePause(row)
  else if (command === 'resume') handleResume(row)
  else if (command === 'reassign') handleReassign(row)
  else if (command === 'redispatch') handleRedispatch(row)
  else if (command === 'restructure' || command === 'restructureEdit') handleRestructure(row)
  else if (command === 'rederive') handleReDerive(row)
  else if (command === 'restructureApply') handleApplyRestructure(row)
}

function getSubTaskStatusMeta(status: SubTask['status']) { return SUB_TASK_STATUS_MAP[status] }

// step9c：按主任务过滤时 10s 自动轮询刷新依赖图（限定条件由 useAutoRefresh 内部判断）。
// 退出页面 / 主任务切换时由 composable 自动停 timer，无需手写 onBeforeUnmount。
const isDagMode = computed(() => viewMode.value === 'dag')
const allDone = computed(
  () => fullList.value.length > 0 && fullList.value.every((s) => s.status === 'DONE')
)
useAutoRefresh(() => { load(currentPage.value) }, {
  intervalMs: 10_000,
  shouldRun: computed(() => isDagMode.value && !!taskId.value && !allDone.value),
  key: computed(() => taskId.value || ''),
  autoStart: true
})

onMounted(() => {
  // 带筛选跳转（池菜单）：先用 query.status 初始化筛选再加载
  const initStatus = statusQuery.value
  const validStatuses: SubTaskStatus[] = [
    'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED', 'REVIEW',
    'DONE', 'REWORK', 'BLOCKED', 'CANCELLED', 'DEAD_LETTER',
    'AWAITING_DERIVED', 'RESTRUCTURING'
  ]
  statusFilter.value = (validStatuses as string[]).includes(initStatus) ? (initStatus as SubTaskStatus) : ''
  loadParentTask()
  load()
  loadDeadLetterCount()
})
</script>

<style scoped>
/* 列表页通用外观（页头/卡片/表格/分页/单元格）由 design-system.css 的
   List Page Template 统一提供，本页只保留子任务专属排版。 */
.score-empty { color: var(--ha-muted); cursor: help; }
.parent-task-bar { margin-bottom: 12px; }
.parent-task-bar :deep(.el-alert__title) { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.parent-task-title { display: flex; align-items: center; }
/* 信息条右侧动作组：胶囊与「查看全部」同一基线并排，space-between 只作用在左右两端 */
.parent-task-actions { display: inline-flex; align-items: center; gap: 12px; }
.dep-tag { cursor: pointer; margin-right: 4px; }
/* 标题前拓扑序号小徽标：参考电商 new 角标样式（小号胶囊、实底白字）。
   与标题、下钻手柄的间距统一由 `.cell-title` 的 gap 给（design-system.css），此处不再自带外边距
   ——两处各写一半会在「徽标 + 标题 + 手柄」同时出现时叠成 12px。 */
.seq-badge {
  display: inline-block;
  padding: 0 6px;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 8px;
  vertical-align: 1px;
}
/* 行内下钻手柄与标题的间距由 `.cell-title` 的 gap 统一给（design-system.css） */
/* 负责人 Agent 名：长名在窄列下不能换行，单行截断 + 原生 title tooltip 提示全名 */
.agent-cell {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ha-ink);
}
/* 状态列内的等待倒计时小标签：紧跟状态标签，不压缩、单行不换行 */
.wait-tag {
  flex: none;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
/* 表格 cell 默认 vertical-align: middle，显式居中作为升级防御，
   保证单行 Agent 名与单行时间戳视觉中线对齐 */
:deep(.el-table td.el-table__cell > .cell) {
  display: flex;
  align-items: center;
}
</style>
