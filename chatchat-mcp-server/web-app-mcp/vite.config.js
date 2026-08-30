const { defineConfig, loadEnv } = require('vite');
const vue = require('@vitejs/plugin-vue');

module.exports = defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_PROXY_TARGET
    || env.VUE_APP_API_PROXY_TARGET
    || 'http://localhost:8090';
  return {
    base: './',
    plugins: [vue.default()],
    server: {
      port: 5178,
      proxy: {
        '/api': { target: apiTarget, changeOrigin: true },
        '/mcp': { target: apiTarget, changeOrigin: true }
      }
    }
  };
});
