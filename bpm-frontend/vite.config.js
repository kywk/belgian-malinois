import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// uni-form spec 資源（manifests／value schemas）的 glob 入口：
// import.meta.glob 只接受 '/' 或 './' 開頭且經 alias 解析後的路徑，
// 因此把套件內的 spec 目錄固定成 alias，供 src/uniform/setup.js 使用。
// 展開目標是 node_modules 內的實體路徑，dev／build 行為一致。
const uniformSpec = fileURLToPath(new URL('./node_modules/@nanshan/uniform-spec/dist/spec', import.meta.url))

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@nanshan/uniform-spec/spec': uniformSpec,
    },
  },
  optimizeDeps: {
    include: ['bpmn-js/lib/NavigatedViewer']
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        // 預設對 docker-compose 的 nginx（:80）；用 localenv 起 bpm-core 時
        // 以 VITE_API_PROXY=http://127.0.0.1:18110 npm run dev 覆寫即可。
        target: process.env.VITE_API_PROXY || 'http://localhost:80',
        changeOrigin: true
      }
    }
  }
})
