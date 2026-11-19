<template>
  <!-- 长文本结构化视图：把「一段话」还原成「标签 + 条目 / 步骤」，只切分不改写原文。
       已自带 markdown 块结构的文本直接交 MarkdownView（见 looksLikeMarkdown）。 -->
  <MarkdownView
    v-if="asMarkdown"
    :content="text"
    :compact="compact"
  />
  <div
    v-else
    class="stv"
    :class="{ 'stv--compact': compact }"
  >
    <template
      v-for="(block, idx) in blocks"
      :key="idx"
    >
      <!-- 带标签段：标签做行内色块前缀，正文/条目跟随其后。
           不排成左右两栏——`动作 (只读, 按原样执行并保留原始 stdout)` 这类长标签
           放进窄栏会折成三四行，比不格式化还难读。 -->
      <div
        v-if="block.kind === 'labeled'"
        class="stv-item"
      >
        <div class="stv-line">
          <span class="stv-label">{{ block.label }}</span>
          <span
            v-if="block.text"
            class="stv-text"
            v-html="inline(block.text)"
          />
        </div>
        <div
          v-if="block.lead"
          class="stv-lead"
          v-html="inline(block.lead)"
        />
        <ol
          v-if="block.items"
          class="stv-list"
        >
          <li
            v-for="(it, i) in block.items"
            :key="i"
            v-html="inline(it)"
          />
        </ol>
      </div>
      <!-- 步骤流水（先…。随后…。再…。最后…）：编号徽章 + 左对齐正文，
           超长步骤内部的 ①…⑦ 子清单再缩进一层 -->
      <ol
        v-else-if="block.kind === 'steps'"
        class="stv-steps"
      >
        <li
          v-for="(s, i) in block.steps || []"
          :key="i"
          class="stv-step"
        >
          <span class="stv-step-no">{{ i + 1 }}</span>
          <div class="stv-step-body">
            <p
              v-if="s.text"
              class="stv-para"
              v-html="inline(s.text)"
            />
            <template v-else>
              <p
                v-if="s.subLead"
                class="stv-para"
                v-html="inline(s.subLead)"
              />
              <ol
                v-if="s.subItems?.length"
                class="stv-sublist"
              >
                <li
                  v-for="(it, k) in s.subItems"
                  :key="k"
                  v-html="inline(it)"
                />
              </ol>
            </template>
          </div>
        </li>
      </ol>
      <!-- 无标签条目清单（如执行约束逐条禁令） -->
      <ul
        v-else-if="block.kind === 'list'"
        class="stv-list stv-list--dot"
      >
        <li
          v-for="(it, i) in block.items || []"
          :key="i"
          v-html="inline(it)"
        />
      </ul>
      <p
        v-else
        class="stv-para"
        v-html="inline(block.text || '')"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { looksLikeMarkdown, parseStructuredText, renderInline } from '@/utils/structuredText'

const props = withDefaults(defineProps<{
  text: string | null | undefined
  /** 逐条禁令类文本（执行约束）：允许按 `；` 拆条目 */
  splitSemicolon?: boolean
  /** 紧凑排版（次要层级用） */
  compact?: boolean
}>(), { splitSemicolon: false, compact: false })

const asMarkdown = computed(() => looksLikeMarkdown(props.text))
const blocks = computed(() => parseStructuredText(props.text, { splitSemicolon: props.splitSemicolon }))

/** 行内渲染（`命令` → 代码片、**强调** → 粗体）；已在工具层完成转义 */
function inline(text: string): string {
  return renderInline(text)
}
</script>

<style scoped>
.stv {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.stv--compact { gap: 6px; }
.stv-item { min-width: 0; }
/* 标签行：标签与正文同一行起始，长正文自然在标签后换行，不会撑出窄栏 */
.stv-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 7px;
  min-width: 0;
}
.stv-label {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--ha-primary-muted, rgba(124, 58, 237, 0.12));
  color: var(--ha-primary, #7C3AED);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  letter-spacing: 0.01em;
}
.stv-text {
  flex: 1 1 260px;
  min-width: 0;
  line-height: 1.8;
  word-break: break-word;
}
.stv-lead {
  margin-top: 5px;
  line-height: 1.8;
  color: var(--ha-ink-secondary, inherit);
  word-break: break-word;
}
.stv-para {
  margin: 0;
  line-height: 1.8;
  word-break: break-word;
}
/* 条目清单：缩进 + 序号色，让「逐条」这件事一眼可见 */
.stv-list {
  margin: 9px 0 0;
  padding-left: 22px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stv-list > li {
  line-height: 1.75;
  word-break: break-word;
  padding-left: 2px;
}
.stv-list > li::marker {
  color: var(--ha-primary, #7C3AED);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.stv-list--dot { list-style: disc outside; }

/* 步骤流水：编号徽章独立成列，正文换行时不会缩到徽章下面 */
.stv-steps {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.stv-step {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
}
.stv-step-no {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  border-radius: 50%;
  background: var(--ha-primary-muted, rgba(124, 58, 237, 0.12));
  color: var(--ha-primary, #7C3AED);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.stv-step-body {
  min-width: 0;
}
/* 步骤内部的子清单（①…⑦）：再缩一层，与步骤正文区分 */
.stv-sublist {
  margin: 6px 0 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  list-style: decimal outside;
}
.stv-sublist > li {
  line-height: 1.7;
  word-break: break-word;
}
.stv-sublist > li::marker {
  color: var(--ha-muted, #8A93A5);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* v-html 内容不参与 scoped 编译，行内代码与强调需穿透 */
.stv :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--ha-surface-hover, rgba(0, 0, 0, 0.05));
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  /* 不用 break-all：它会把 `--abbrev-ref HEAD` 从参数中间劈开（`--ab` / `brev-ref`），
     代码底色跟着断成两块，看起来像两个不同的命令。优先在空格处断，
     只有单个超长 token（仓库 URL）才强制断。 */
  word-break: normal;
  overflow-wrap: anywhere;
  /* 长命令必然跨行：clone 让每一行片段都自带完整的圆角与内边距，
     否则跨行处只有一个直角断口，像被截断的标签。 */
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
.stv :deep(strong) {
  font-weight: 600;
  color: var(--ha-ink, inherit);
}
</style>
