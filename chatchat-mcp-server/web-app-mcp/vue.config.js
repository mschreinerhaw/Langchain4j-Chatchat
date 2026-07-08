const apiTarget = process.env.VUE_APP_API_PROXY_TARGET || 'http://localhost:8090';

module.exports = {
  publicPath: './',
  outputDir: 'dist',
  devServer: {
    port: 5178,
    client: {
      overlay: {
        errors: true,
        warnings: false,
        runtimeErrors: error => {
          const message = error?.message || String(error || '');
          return !message.includes('ResizeObserver loop completed with undelivered notifications')
            && !message.includes('ResizeObserver loop limit exceeded');
        }
      }
    },
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      },
      '/mcp': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  }
};
