/* eslint-env node */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true
  },
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    'plugin:@typescript-eslint/recommended'
  ],
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 2022,
    sourceType: 'module',
    extraFileExtensions: ['.vue']
  },
  plugins: ['@typescript-eslint', 'vue'],
  rules: {
    // 工程内的历史代码已经定型，先以「错误而非警告」开启收敛，必要时按文件级豁免
    'vue/multi-word-component-names': 'off',
    'vue/no-v-html': 'off',
    // 项目大量使用 any（埋点/上下文/接口对接遗留），收紧前先保留 warning
    '@typescript-eslint/no-explicit-any': 'warn',
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    // Element Plus 的 el-icon / el-form 等是全局组件，关闭 no-undef
    'vue/no-unused-components': 'warn',
    // 模板里常用 (route.query.x as string) 这类 Vue Router 类型推导限制，后续逐步替换
    '@typescript-eslint/no-non-null-assertion': 'off',
    'no-empty': ['warn', { allowEmptyCatch: true }],

    // ========================================================================
    // 长整型 ID 精度门禁（2026-10-30）
    // ========================================================================
    // 后端主键是雪花 Long（19 位，> Number.MAX_SAFE_INTEGER），序列化为 JSON 字符串。
    // 一旦在前端转成 number，超出 53 位的部分被**静默舍入**：2115975708197916799 →
    // 2115975708197916700，接口返回 200、字段看着正常，但后端起查不到 ⇒ 只看到
    // 「XX 不存在」这类业务报错，排查成本极高（TeamList 添加成员即踩此坑）。
    //
    // 规则：ID 全程按 string 透传，比较用 utils/id.ts 的 sameId，需要字符串用 toId。
    // 下面按「变量名以 Id/ID 结尾」识别 ID 形参（与 JacksonConfig 的后端判定同口径）。
    'no-restricted-syntax': [
      'error',
      {
        selector: "CallExpression[callee.name='Number'][arguments.0.name=/([Ii]d|ID)$/]",
        message: '禁止对 ID 使用 Number()：雪花 ID 为 19 位，超过 Number 安全整数上限（2^53-1），转换会静默丢精度（末尾变 00）。ID 请按 string 透传（见 utils/id.ts）。'
      },
      {
        selector: "CallExpression[callee.name='Number'][arguments.0.property.name=/([Ii]d|ID)$/]",
        message: '禁止对 ID 使用 Number()：雪花 ID 为 19 位，超过 Number 安全整数上限（2^53-1），转换会静默丢精度（末尾变 00）。ID 请按 string 透传（见 utils/id.ts）。'
      },
      {
        selector: "CallExpression[callee.name=/^(parseInt|parseFloat)$/][arguments.0.name=/([Ii]d|ID)$/]",
        message: '禁止对 ID 使用 parseInt/parseFloat：雪花 ID 超出 Number 安全整数上限，转换会静默丢精度。ID 请按 string 透传（见 utils/id.ts）。'
      },
      {
        selector: "CallExpression[callee.name=/^(parseInt|parseFloat)$/][arguments.0.property.name=/([Ii]d|ID)$/]",
        message: '禁止对 ID 使用 parseInt/parseFloat：雪花 ID 超出 Number 安全整数上限，转换会静默丢精度。ID 请按 string 透传（见 utils/id.ts）。'
      },
      {
        selector: "UnaryExpression[operator='+'][argument.name=/([Ii]d|ID)$/]",
        message: '禁止用一元 + 把 ID 转成数字：雪花 ID 会静默丢精度。ID 请按 string 透传（见 utils/id.ts）。'
      },
      {
        selector: "UnaryExpression[operator='+'][argument.property.name=/([Ii]d|ID)$/]",
        message: '禁止用一元 + 把 ID 转成数字：雪花 ID 会静默丢精度。ID 请按 string 透传（见 utils/id.ts）。'
      }
    ]
  },
  overrides: [
    {
      files: ['*.config.{js,ts}', 'vite.config.ts', 'env.d.ts'],
      rules: {
        '@typescript-eslint/no-var-requires': 'off'
      }
    }
  ],
  ignorePatterns: ['dist/', 'node_modules/', '*.d.ts']
}