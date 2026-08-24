<template>
  <el-dialog
    v-model="visible"
    title="添加模型"
    width="520px"
    append-to-body
    :close-on-click-modal="phase !== 'done'"
    @close="emit('update:modelValue', false)"
  >
    <el-form
      v-if="phase === 'form'"
      ref="formRef"
      :model="draft"
      :rules="rules"
      label-width="90px"
    >
      <el-form-item
        label="供应商"
        prop="providerCode"
      >
        <el-select
          v-model="draft.providerCode"
          style="width: 100%"
        >
          <el-option
            v-for="p in PROVIDER_CATALOG"
            :key="p.providerCode"
            :label="p.providerName + (isConfigured(p.providerCode) ? '（已添加）' : '')"
            :value="p.providerCode"
          />
          <el-option
            label="自定义供应商…"
            :value="CUSTOM_CODE"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        label="类型"
        prop="billingType"
      >
        <el-select
          v-model="draft.billingType"
          style="width: 100%"
        >
          <el-option
            v-for="opt in BILLING_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
            :disabled="opt.disabled"
          />
        </el-select>
        <div class="form-hint">
          当前仅支持按量付费（API Key），Token Plan / Coding Plan 敬请期待
        </div>
      </el-form-item>
      <el-form-item
        label="API 密钥"
        prop="apiKey"
      >
        <el-input
          v-model="draft.apiKey"
          type="password"
          show-password
          :placeholder="existingProvider ? '输入新 Key，将覆盖现有密钥' : '输入供应商 API Key'"
        />
        <a
          v-if="selectedEntry"
          class="key-link"
          :href="selectedEntry.apiKeyUrl"
          target="_blank"
          rel="noopener noreferrer"
        >获取 API 密钥</a>
      </el-form-item>
      <el-alert
        v-if="existingProvider"
        type="warning"
        :closable="false"
        show-icon
        class="overwrite-alert"
      >
        <template #title>
          该供应商已配置 API Key（{{ existingProvider.apiKeyMasked || '已脱敏' }}），保存后将覆盖
        </template>
      </el-alert>
      <template v-if="isCustom">
        <el-form-item
          label="显示名称"
          prop="customName"
        >
          <el-input
            v-model="draft.customName"
            placeholder="如 我的 OpenAI"
          />
        </el-form-item>
        <el-form-item
          label="Provider Code"
          prop="customCode"
        >
          <el-input
            v-model="draft.customCode"
            placeholder="小写字母数字中划线，如 my-openai"
          />
        </el-form-item>
        <el-form-item
          label="协议类型"
          prop="customProtocol"
        >
          <el-select
            v-model="draft.customProtocol"
            style="width: 100%"
          >
            <el-option
              v-for="opt in PROTOCOL_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="Base URL"
          prop="customBaseUrl"
        >
          <el-input
            v-model="draft.customBaseUrl"
            placeholder="如 https://api.openai.com/v1"
          />
        </el-form-item>
        <el-form-item label="默认模型">
          <el-input
            v-model="draft.customDefaultModel"
            placeholder="如 gpt-4o（可留空，稍后在模型管理中配置）"
          />
        </el-form-item>
      </template>
    </el-form>

    <!-- 保存完成：展示 Key 验证结果（最小请求探测） -->
    <div
      v-else
      class="verify-panel"
    >
      <el-alert
        v-if="verifyResult"
        :type="verifyResult.success ? 'success' : 'error'"
        :closable="false"
        show-icon
      >
        <template #title>
          {{ verifyResult.success ? 'API Key 验证通过' : 'API Key 验证未通过' }}
        </template>
        <div class="verify-message">
          {{ verifyResult.message }}
        </div>
      </el-alert>
      <div class="verify-hint">
        密钥已保存并实时生效。验证以最小请求（max_tokens=1）直探供应商端点，不产生实质用量。
      </div>
    </div>

    <template #footer>
      <template v-if="phase === 'form'">
        <el-button @click="visible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting || verifying"
          @click="handleSubmit"
        >
          {{ verifying ? '正在验证 API Key…' : '添加' }}
        </el-button>
      </template>
      <template v-else>
        <el-button
          v-if="verifyResult && verifyResult.supported !== false"
          :loading="verifying"
          @click="retryVerify"
        >
          重新验证
        </el-button>
        <el-button
          type="primary"
          @click="visible = false"
        >
          完成
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  settingsApi,
  BILLING_TYPE_OPTIONS,
  PROTOCOL_OPTIONS,
  type LlmProviderResponse,
  type ProtocolType,
  type ApiKeyVerifyResult
} from '@/api/settings'
import { PROVIDER_CATALOG, findCatalogProvider, type CatalogProvider } from './providerCatalog'

/** 供应商下拉中的自定义占位值。 */
const CUSTOM_CODE = '__custom__'

const props = defineProps<{
  modelValue: boolean
  providers: LlmProviderResponse[]
  initial: CatalogProvider | null
}>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  added: [providerId: number]
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const formRef = ref<FormInstance>()
const submitting = ref(false)
const verifying = ref(false)
const phase = ref<'form' | 'done'>('form')
const verifyResult = ref<ApiKeyVerifyResult | null>(null)
const savedProviderId = ref<number | null>(null)

const draft = reactive({
  providerCode: '',
  billingType: 'API_KEY',
  apiKey: '',
  customName: '',
  customCode: '',
  customProtocol: 'OPENAI_COMPATIBLE' as ProtocolType,
  customBaseUrl: '',
  customDefaultModel: ''
})

const selectedEntry = computed(() => findCatalogProvider(draft.providerCode))
const isCustom = computed(() => draft.providerCode === CUSTOM_CODE)
const existingProvider = computed(() =>
  props.providers.find(p => p.providerCode === draft.providerCode) || null
)

function isConfigured(providerCode: string): boolean {
  const p = props.providers.find(x => x.providerCode === providerCode)
  return !!p && p.apiKeyConfigured
}

const rules = computed<FormRules>(() => {
  const base: FormRules = {
    providerCode: [{ required: true, message: '请选择供应商', trigger: 'change' }],
    billingType: [{ required: true, message: '请选择类型', trigger: 'change' }],
    apiKey: [{ required: true, message: '请输入 API 密钥', trigger: 'blur' }]
  }
  if (!isCustom.value) return base
  return {
    ...base,
    customName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
    customCode: [
      { required: true, message: '请输入 Provider Code', trigger: 'blur' },
      { pattern: /^[a-z0-9][a-z0-9-]{1,63}$/, message: '全小写字母数字中划线，长度 2-64', trigger: 'blur' }
    ],
    customProtocol: [{ required: true, message: '请选择协议类型', trigger: 'change' }],
    customBaseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
  }
})

// 打开弹窗：按第一步选中的目录条目重置表单
watch(visible, (v) => {
  if (!v) return
  phase.value = 'form'
  verifyResult.value = null
  savedProviderId.value = null
  draft.providerCode = props.initial?.providerCode || PROVIDER_CATALOG[0].providerCode
  draft.billingType = 'API_KEY'
  draft.apiKey = ''
  draft.customName = ''
  draft.customCode = ''
  draft.customProtocol = 'OPENAI_COMPATIBLE'
  draft.customBaseUrl = ''
  draft.customDefaultModel = ''
  formRef.value?.clearValidate()
})

/** 保存密钥后自动验证：预置供应商复用既有行，自定义供应商先创建。 */
async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    let providerId: number
    if (existingProvider.value) {
      providerId = existingProvider.value.id
      // 预置供应商重新添加时同步更新计费类型（编辑入口之外的唯一写路径）
      await settingsApi.updateLlmProvider(providerId, { billingType: draft.billingType })
    } else {
      const entry = selectedEntry.value
      const created = await settingsApi.createLlmProvider({
        providerCode: isCustom.value ? draft.customCode.trim() : entry!.providerCode,
        providerName: isCustom.value ? draft.customName.trim() : entry!.providerName,
        protocolType: isCustom.value ? draft.customProtocol : entry!.protocolType,
        baseUrl: isCustom.value ? draft.customBaseUrl.trim() : entry!.baseUrl,
        defaultModel: isCustom.value
          ? (draft.customDefaultModel.trim() || undefined)
          : entry!.defaultModel,
        billingType: draft.billingType,
        enabled: 1
      })
      providerId = created.id
    }
    await settingsApi.saveLlmProviderApiKey(providerId, draft.apiKey.trim())
    savedProviderId.value = providerId
    emit('added', providerId)
    await runVerify(providerId)
    phase.value = 'done'
  } catch (e: any) {
    ElMessage.error('保存失败')
  } finally {
    submitting.value = false
  }
}

/** 重新验证（结果未通过或想再确认时）。 */
async function retryVerify() {
  if (!savedProviderId.value) return
  await runVerify(savedProviderId.value)
}

async function runVerify(providerId: number) {
  verifying.value = true
  try {
    verifyResult.value = await settingsApi.verifyLlmProviderApiKey(providerId)
  } catch (e: any) {
    verifyResult.value = { success: false, message: '验证请求发送失败，请稍后在供应商详情中重试' }
  } finally {
    verifying.value = false
  }
}
</script>

<style scoped>
.form-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ha-ink-secondary);
}
/* 「获取 API 密钥」绿色链接（对照设计稿） */
.key-link {
  display: inline-block;
  margin-top: 6px;
  font-size: 13px;
  color: var(--ha-success-text);
  text-decoration: none;
}
.key-link:hover {
  text-decoration: underline;
}
.overwrite-alert {
  margin-bottom: 14px;
}
.verify-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}
.verify-message {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}
.verify-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
}
</style>
