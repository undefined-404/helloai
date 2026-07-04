<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>规则配置</span>
          <el-button size="small" type="primary" @click="openCreate">新建规则</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading" style="width:100%">
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ruleType==='global'?'danger':row.ruleType==='module'?'warning':''">{{ row.ruleType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无规则" />

      <el-dialog v-model="editDialog" :title="editing.id ? '编辑规则' : '新建规则'" width="650px" top="5vh">
        <el-form ref="formRef" :model="editForm" :rules="rules" label-width="80px">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="名称" prop="name"><el-input v-model="editForm.name" /></el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="类型" prop="ruleType">
                <el-select v-model="editForm.ruleType" style="width:100%">
                  <el-option label="全局" value="global" />
                  <el-option label="模块" value="module" />
                  <el-option label="Agent" value="agent" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="优先级"><el-input-number v-model="editForm.priority" :min="0" :max="100" /></el-form-item>
          <el-form-item label="内容" prop="content"><el-input v-model="editForm.content" type="textarea" :rows="12" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="editDialog=false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ruleApi } from '@/api/rule'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<any[]>([])
const loading = ref(false)
const editDialog = ref(false)
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
.page { max-width: var(--ha-content-width); }
</style>
