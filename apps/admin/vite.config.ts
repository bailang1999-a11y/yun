import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/vue') || id.includes('node_modules/vue-router') || id.includes('node_modules/pinia')) {
            return 'vue'
          }
          const elementComponent = id.match(/node_modules\/element-plus\/(?:es|lib)\/components\/([^/]+)/)
          if (elementComponent) {
            return `element-${elementComponent[1]}`
          }
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) {
            return 'element'
          }
          if (id.includes('node_modules/echarts')) {
            return 'charts'
          }
          if (id.includes('node_modules/lucide-vue-next')) {
            return 'icons'
          }
        }
      }
    }
  }
})
