// ESLint for the deep test rig (tools/deep-desktop.sh): eslint:recommended plus a few checks that catch real bugs
// (undefined names, unreachable code, duplicate keys, loops that never change, self-comparisons). Not part of
// `npm test`; eslint and globals are installed only for the rig (npm i --no-save), so the app's dependencies stay as
// they are. Run: npx eslint -c test/eslint.deep.mjs *.js renderer scripts
import js from '@eslint/js';
import globals from 'globals';

const bugs = {
  'no-unused-vars': ['warn', { args: 'none', caughtErrors: 'none', ignoreRestSiblings: true, varsIgnorePattern: '^_' }],
  'no-undef': 'error',
  'no-self-compare': 'error',
  'no-unreachable-loop': 'error',
  'no-unmodified-loop-condition': 'error',
  'no-constructor-return': 'error',
  'no-template-curly-in-string': 'warn',
  'array-callback-return': ['warn', { checkForEach: false }],
  'no-promise-executor-return': 'warn',
  'default-case-last': 'warn',
  'no-use-before-define': ['warn', { functions: false, classes: false, variables: true, allowNamedExports: true }],
  'no-empty': ['warn', { allowEmptyCatch: true }],
};
export default [
  { ignores: ['node_modules/**', 'dist/**', 'test/**', 'voice-server/**', 'renderer/fonts/**'] },
  { ...js.configs.recommended, files: ['**/*.js'] },
  // the main process and its helpers: CommonJS on Node (Electron's main)
  { files: ['*.js', 'scripts/**/*.js'], languageOptions: { ecmaVersion: 2024, sourceType: 'commonjs', globals: { ...globals.node } }, rules: bugs },
  // the pages: ES modules in Chromium, with the preload's bridge
  { files: ['renderer/**/*.js'], languageOptions: { ecmaVersion: 2024, sourceType: 'module', globals: { ...globals.browser, atlasHost: 'readonly' } }, rules: bugs },
];
