import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElButton,
  ElButtonGroup,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload
} from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

;[
  ElButton,
  ElButtonGroup,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElOption,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload
].forEach((component) => {
  app.use(component)
})

app.use(createPinia()).use(router).mount('#app')
