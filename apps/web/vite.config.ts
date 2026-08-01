import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // altcha-widget 是 Web Component，交给浏览器解析，
          // 否则 Vue 会打印 "Failed to resolve component" 警告。
          isCustomElement: (tag) => tag === 'altcha-widget'
        }
      }
    })
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/vue') || id.includes('node_modules/vue-router') || id.includes('node_modules/pinia')) return 'vue'
          if (id.includes('node_modules/lucide-vue-next')) return 'icons'
        }
      }
    }
  }
})
