import eslintPluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import vueTsEslintConfig from '@vue/eslint-config-typescript'

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', '*.d.ts'] },
  ...tseslint.configs.recommended,
  ...eslintPluginVue.configs['flat/recommended'],
  ...vueTsEslintConfig(),
  {
    rules: {
      // 禁止调试性 console.log 残留；保留 console.warn / console.error（异常路径日志）
      'no-console': ['error', { allow: ['warn', 'error'] }],
      // 未使用变量由 vue-tsc（noUnusedLocals）兜底，ESLint 层放行模板绑定
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      // 事件处理函数名不需要强制 vue/multi-word 前缀（单组件页面项目）
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      // 项目既有模板属性顺序风格（class 先于 @click 等），不强制重排
      'vue/attributes-order': 'off',
      // 业务代码存在少量 any（非系统边界），由 vue-tsc 严格模式保证类型安全，此处降为提示
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
)
