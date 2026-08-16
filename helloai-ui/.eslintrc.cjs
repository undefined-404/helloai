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
    'no-empty': ['warn', { allowEmptyCatch: true }]
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