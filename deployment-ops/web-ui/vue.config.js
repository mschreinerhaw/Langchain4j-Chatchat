const { defineConfig } = require('@vue/cli-service');
module.exports = defineConfig({
  productionSourceMap: false,
  devServer: {
    host: '127.0.0.1',
    port: 19241,
    proxy: { '/api': { target: 'http://127.0.0.1:19240' }, '/actuator': { target: 'http://127.0.0.1:19240' } }
  }
});
