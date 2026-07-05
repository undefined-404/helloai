<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>模板管理</span>
          <el-button size="small" type="primary" @click="openCreate">新建模板</el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-select v-model="filterRole" placeholder="按角色筛选" clearable style="width:160px" @change="load">
          <el-option label="PLANNER" value="PLANNER" />
          <el-option label="EXECUTOR" value="EXECUTOR" />
          <el-option label="REVIEWER" value="REVIEWER" />
          <el-option label="PATROL" value="PATROL" />
        </el-select>
        <el-select v-model="filterCategory" placeholder="按分类筛选" clearable style="width:180px;margin-left:8px" @change="load">
          <el-option v-for="[k,v] in Object.entries(PROMPT_CATEGORY_MAP)" :key="k" :label="v" :value="k" />
        </el-select>
      </div>

      <el-table :data="list" border stripe v-loading="loading" style="width:100%;margin-top:12px">
        <el-table-column prop="name" label="名称" min-width="60" show-overflow-tooltip />
        <el-table-column label="角色" width="100">
          <template #default="{ row }"><el-tag size="small" :type="roleTag(row.role)">{{ row.role }}</el-tag></template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }"><el-tag size="small" type="info">{{ PROMPT_CATEGORY_MAP[row.category] || row.category }}</el-tag></template>
        </el-table-column>
        <el-table-column label="默认" width="60">
          <template #default="{ row }"><el-tag v-if="row.isDefault" size="small" type="success">●</el-tag><span v-else>-</span></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="60" />
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="ACTION.FOUR" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" @click="previewCompose(row)">组合</el-button>
              <el-button v-if="row.category==='SKILL'" size="small" type="warning" @click="previewSkill(row)">Skill</el-button>
              <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无 Prompt 模板" />
    </el-card>

    <el-dialog v-model="editDialog" :title="editing.id ? '编辑模板' : '新建模板'" width="800px" top="5vh" append-to-body>
      <el-form ref="formRef" :model="editForm" :rules="rules" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="名称" prop="name"><el-input v-model="editForm.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="角色" prop="role"><el-select v-model="editForm.role" style="width:100%"><el-option v-for="r in ['PLANNER','EXECUTOR','REVIEWER','PATROL']" :key="r" :label="r" :value="r" /></el-select></el-form-item></el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="分类" prop="category"><el-select v-model="editForm.category" style="width:100%"><el-option label="角色模板" value="ROLE_TEMPLATE" /><el-option label="Agent 专业化" value="AGENT_SPECIALIZATION" /><el-option label="技能文档" value="SKILL" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="Slug"><el-input v-model="editForm.slug" placeholder="AGENT_SPECIALIZATION 时必填" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="描述"><el-input v-model="editForm.description" /></el-form-item>
        <el-form-item label="内容" prop="content"><el-input v-model="editForm.content" type="textarea" :rows="18" /></el-form-item>
        <el-row :gutter="16">
          <el-col :span="8"><el-form-item label="默认模板"><el-switch v-model="editForm.isDefault" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="示例模板"><el-switch v-model="editForm.isExample" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="editDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="handleSave">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="composeDialog" title="Prompt 组合预览" width="800px" top="5vh" append-to-body>
      <el-input v-model="composeResult" type="textarea" :rows="20" readonly />
      <template #footer><el-button @click="composeDialog=false">关闭</el-button><el-button type="primary" @click="copyCompose">复制</el-button></template>
    </el-dialog>

    <el-dialog v-model="skillDialog" title="SKILL 预览" width="700px" top="5vh" append-to-body>
      <el-input v-model="skillContent" type="textarea" :rows="18" readonly />
      <template #footer><el-button @click="skillDialog=false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { promptApi } from '@/api/prompt'
import { PROMPT_CATEGORY_MAP } from '@/types'
import { ACTION, fmtTime } from '@/utils/tableConfig'

const list = ref<any[]>([])
const loading = ref(false)
const filterRole = ref('')
const filterCategory = ref('')
const editDialog = ref(false)
const saving = ref(false)
const formRef = ref()
const editing = reactive<Partial<any>>({})
const editForm = reactive({ name: '', role: 'EXECUTOR', category: 'ROLE_TEMPLATE', slug: '', description: '', content: '', isDefault: 0, isExample: 0 })
const rules = { name: [{ required: true }], role: [{ required: true }], category: [{ required: true }], content: [{ required: true }] }
const composeDialog = ref(false)
const composeResult = ref('')
const skillDialog = ref(false)
const skillContent = ref('')

const roleMap: Record<string, string> = { PLANNER: '', EXECUTOR: 'primary', REVIEWER: 'success', PATROL: 'warning' }
function roleTag(role: string) { return roleMap[role] || '' }

async function load() {
  loading.value = true
  try {
    const params: any = {}
    if (filterRole.value) params.role = filterRole.value
    if (filterCategory.value) params.category = filterCategory.value
    list.value = await promptApi.list(params)
  } finally { loading.value = false }
}
function openCreate() {
  Object.assign(editing, {})
  Object.assign(editForm, { name: '', role: 'EXECUTOR', category: 'ROLE_TEMPLATE', slug: '', description: '', content: '', isDefault: 0, isExample: 0 })
  editDialog.value = true
}
function openEdit(row: any) {
  Object.assign(editing, row)
  Object.assign(editForm, { name: row.name, role: row.role, category: row.category, slug: row.slug || '', description: row.description || '', content: row.content, isDefault: row.isDefault, isExample: row.isExample })
  editDialog.value = true
}
async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.id) { await promptApi.update(editing.id, editForm); ElMessage.success('更新成功') }
    else { await promptApi.create(editForm); ElMessage.success('创建成功') }
    editDialog.value = false; load()
  } catch (e) { /* */ } finally { saving.value = false }
}
async function handleDelete(row: any) {
  await ElMessageBox.confirm(`确定删除「${row.name}」？`, '确认删除', { type: 'warning' })
  await promptApi.remove(row.id); ElMessage.success('已删除'); load()
}
async function previewCompose(row: any) {
  try { const res = await promptApi.compose(row.role, row.content); composeResult.value = res.content || ''; composeDialog.value = true } catch (e) { /* */ }
}
function previewSkill(row: any) { skillContent.value = row.content; skillDialog.value = true }
function copyCompose() { navigator.clipboard.writeText(composeResult.value); ElMessage.success('已复制到剪贴板') }
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.filter-bar { display: flex; align-items: center; }
</style>
