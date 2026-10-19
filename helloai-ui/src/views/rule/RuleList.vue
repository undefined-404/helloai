<template>
  <div class="page list-page ha-entrance-up">
    <div class="page-head">
      <div class="page-head-title">
        <div class="dash-icon-block primary">
          <el-icon><SetUp /></el-icon>
        </div>
        <div>
          <h2 class="page-heading">
            规则配置
            <span
              v-if="list.length"
              class="query-stats"
            >共 {{ list.length }} 条</span>
          </h2>
          <p class="page-subheading">
            派单、评分与熔断规则的统一配置
          </p>
        </div>
      </div>
      <div class="page-head-actions">
        <el-button
          size="small"
          @click="load"
        >
          刷新
        </el-button>
        <el-button
          v-auth="'rule:add'"
          size="small"
          type="primary"
          @click="openCreate"
        >
          新建规则
        </el-button>
      </div>
    </div>
    <el-card class="list-card">
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          prop="name"
          label="名称"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          label="类型"
          width="100"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="typeTag(row.ruleType)"
            >
              {{ typeLabel(row.ruleType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="priority"
          label="优先级"
          width="88"
        />
        <el-table-column
          label="更新时间"
          width="180"
        >
          <template #default="{ row }">
            {{ fmtTime(row.updateTime) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="220"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-cell">
              <el-button
                v-auth="'rule:view'"
                size="small"
                @click="openView(row)"
              >
                查看
              </el-button>
              <el-button
                v-auth="'rule:edit'"
                size="small"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                v-auth="'rule:delete'"
                size="small"
                type="danger"
                plain
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        description="暂无规则"
      />

      <el-dialog
        v-model="editDialog"
        :title="editing.id ? '编辑规则' : '新建规则'"
        width="650px"
        top="5vh"
        append-to-body
      >
        <el-form
          ref="formRef"
          :model="editForm"
          :rules="rules"
          label-width="80px"
        >
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item
                label="名称"
                prop="name"
              >
                <el-input v-model="editForm.name" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item
                label="类型"
                prop="ruleType"
              >
                <el-select
                  v-model="editForm.ruleType"
                  style="width:100%"
                >
                  <el-option
                    label="全局"
                    value="global"
                  />
                  <el-option
                    label="模块"
                    value="module"
                  />
                  <el-option
                    label="Agent"
                    value="agent"
                  />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="优先级">
            <el-input-number
              v-model="editForm.priority"
              :min="0"
              :max="100"
            />
          </el-form-item>
          <el-form-item
            label="内容"
            prop="content"
          >
            <!-- 正文是 Markdown（渲染在「查看」弹窗）：编辑态给等宽字体 + 语法提示，
                 让换行/表格对齐在文本域里就是对的，而不是保存后才发现错位 -->
            <el-input
              v-model="editForm.content"
              type="textarea"
              :rows="14"
              class="rule-editor"
              placeholder="支持 Markdown：## 标题、| 表格 |、``` 代码块 ```"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="editDialog=false">
            取消
          </el-button>
          <el-button
            type="primary"
            :loading="saving"
            @click="handleSave"
          >
            保存
          </el-button>
        </template>
      </el-dialog>

      <!-- 只读查看弹窗：GUEST 等只读角色唯一的规则内容入口。
           规则正文本身是 Markdown（标题 / 表格 / 状态流转代码块），早先用 pre-wrap 直出
           ⇒ `##`、`|` 全裸露成「一堆符号」（2026-10-18 用户反馈样式乱）。
           现改用统一 MarkdownView，与对话 / 报告页同一套排版。 -->
      <el-dialog
        v-model="viewDialog"
        title="查看规则"
        width="880px"
        top="6vh"
        append-to-body
      >
        <!-- 元信息：紧凑标签行。原 el-form-item 固定 label-width=80 + 行间距，
             四行元信息占了小半屏，且「更新时间」等长标签被挤成方块。 -->
        <div class="rule-meta">
          <div class="rule-meta__item">
            <span class="rule-meta__k">名称</span>
            <span class="rule-meta__v">{{ viewRow?.name || '-' }}</span>
          </div>
          <div class="rule-meta__item">
            <span class="rule-meta__k">类型</span>
            <el-tag
              size="small"
              :type="typeTag(viewRow?.ruleType)"
            >
              {{ typeLabel(viewRow?.ruleType) }}
            </el-tag>
          </div>
          <div class="rule-meta__item">
            <span class="rule-meta__k">优先级</span>
            <span class="rule-meta__v">{{ viewRow?.priority ?? '-' }}</span>
          </div>
          <div class="rule-meta__item">
            <span class="rule-meta__k">更新时间</span>
            <span class="rule-meta__v">{{ fmtTime(viewRow?.updateTime) }}</span>
          </div>
        </div>
        <div class="rule-body">
          <MarkdownView :content="viewRow?.content" />
        </div>
        <template #footer>
          <el-button @click="viewDialog=false">
            关闭
          </el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { SetUp } from '@element-plus/icons-vue'
import { ruleApi } from '@/api/rule'
import { fmtTime } from '@/utils/tableConfig'
import MarkdownView from '@/components/MarkdownView.vue'
import type { Rule } from '@/types'

/**
 * 规则类型只用于展示（后端存字符串）：给中文名 + 标签色，
 * 避免列表与详情里出现裸露的 `global` / `module` 英文码。
 * 若要并入后端枚举字典（`/api/enums/catalog`），只需在此处换成 `enumStore.labelOf('ruleType', code)`。
 */
const RULE_TYPE_LABEL: Record<string, string> = { global: '全局', module: '模块', agent: 'Agent' }
const RULE_TYPE_TAG: Record<string, 'danger' | 'warning' | 'info'> = { global: 'danger', module: 'warning', agent: 'info' }

function typeLabel(t?: string | null): string {
  return (t && RULE_TYPE_LABEL[t]) || t || '-'
}
function typeTag(t?: string | null): 'danger' | 'warning' | 'info' {
  return (t && RULE_TYPE_TAG[t]) || 'info'
}

const list = ref<any[]>([])
const loading = ref(false)
const editDialog = ref(false)
const viewDialog = ref(false)
const viewRow = ref<Rule | null>(null)
const saving = ref(false)
const formRef = ref()
const editing = reactive<Partial<any>>({})
const editForm = reactive({ name: '', ruleType: 'global', priority: 0, content: '' })
const rules = { name: [{ required: true }], ruleType: [{ required: true }], content: [{ required: true }] }

async function load() { loading.value = true; try { list.value = await ruleApi.list() } finally { loading.value = false } }

function openCreate() {
  Object.assign(editing, {})
  Object.assign(editForm, { name: '', ruleType: 'global', priority: 0, content: '' })
  editDialog.value = true
}
function openEdit(row: any) {
  Object.assign(editing, row)
  Object.assign(editForm, { name: row.name, ruleType: row.ruleType, priority: row.priority, content: row.content })
  editDialog.value = true
}
// 只读查看：直接用列表行数据渲染，不再回查接口（列表已含全字段）
function openView(row: Rule) {
  viewRow.value = row
  viewDialog.value = true
}
async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.id) { await ruleApi.update(editing.id, editForm); ElMessage.success('更新成功') }
    else { await ruleApi.create(editForm); ElMessage.success('创建成功') }
    editDialog.value = false; load()
  } finally { saving.value = false }
}
async function handleDelete(row: any) {
  await ElMessageBox.confirm(`确定删除「${row.name}」？`, '确认删除', { type: 'warning' })
  await ruleApi.remove(row.id)
  ElMessage.success('已删除'); load()
}
onMounted(() => load())
</script>

<style scoped>
/* 列表页通用外观由 design-system.css 的 List Page Template 统一提供。 */

/* ---- 查看规则弹窗（2026-10-18 排版重整）----
   原问题三处：① 元信息四行 el-form-item 占掉小半屏、长标签被挤成方块；
   ② 正文 `white-space: pre-wrap` 直出 ⇒ `##` 与表格 `|` 全裸露；
   ③ 滚动条紧贴文字，长文读起来像挤在缝里。
   改法：元信息压成一行 chips；正文交 MarkdownView 渲染，放进带内边距的滚动面板。 */
.rule-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
  align-items: center;
  padding: 10px 14px;
  margin-bottom: 14px;
  border-radius: 8px;
  background: var(--ha-surface, rgba(127, 127, 127, 0.06));
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.14));
  font-size: 13px;
}
.rule-meta__item { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.rule-meta__k { color: var(--ha-muted, #909399); white-space: nowrap; }
.rule-meta__v { font-weight: 500; color: var(--ha-text, inherit); word-break: break-word; }

/* 正文面板：四周留白 + 独立滚动（不撑高弹窗） */
.rule-body {
  max-height: 60vh;
  overflow-y: auto;
  padding: 16px 20px 18px;
  border: 1px solid var(--ha-border, rgba(127, 127, 127, 0.14));
  border-radius: 8px;
}
/* 细滚动条：8px，避免抢走正文的视觉焦点 */
.rule-body::-webkit-scrollbar { width: 8px; }
.rule-body::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.28);
  border-radius: 4px;
}
.rule-body::-webkit-scrollbar-thumb:hover { background: rgba(127, 127, 127, 0.45); }
.rule-body::-webkit-scrollbar-track { background: transparent; }

/* 编辑态：Markdown 正文用等宽字体，表格/代码块的对齐关系在输入时就成立 */
.rule-editor :deep(textarea) {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.7;
}
</style>
