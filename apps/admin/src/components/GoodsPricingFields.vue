<script setup lang="ts">
import type { PriceTemplate } from '../utils/priceTemplates'

type SelectOption = {
  label: string
  value: string
}

defineProps<{
  priceTemplates: PriceTemplate[]
  statusOptions: SelectOption[]
  accountTypeOptions: SelectOption[]
  deliveryType: string
}>()

const priceTemplateId = defineModel<string | undefined>('priceTemplateId')
const status = defineModel<string>('status', { required: true })
const price = defineModel<number>('price', { required: true })
const originalPrice = defineModel<number | undefined>('originalPrice')
const stock = defineModel<number | undefined>('stock')
const maxBuy = defineModel<number | undefined>('maxBuy')
const accountTypes = defineModel<string[]>('accountTypes', { default: [] })

const emit = defineEmits<{
  applyPriceTemplate: [value?: string]
}>()
</script>

<template>
  <div class="pricing-compact" :class="{ withRecharge: deliveryType !== 'CARD' }">
    <el-form-item label="价格模板" class="pricing-template">
      <el-select v-model="priceTemplateId" placeholder="选择价格模板" @change="emit('applyPriceTemplate', priceTemplateId)">
        <el-option
          v-for="item in priceTemplates"
          :key="item.id"
          :label="`${item.name} · ${item.groupRates?.length || 0} 个会员等级`"
          :value="item.id"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="状态" class="pricing-status">
      <el-select v-model="status">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="售价">
      <el-input-number v-model="price" :min="0" :precision="2" :step="1" :controls="false" />
    </el-form-item>
    <el-form-item label="划线原价">
      <el-input-number v-model="originalPrice" :min="0" :precision="2" :step="1" :controls="false" placeholder="选填" />
    </el-form-item>
    <el-form-item label="库存">
      <el-input-number v-model="stock" :min="0" :step="1" :controls="false" />
    </el-form-item>
    <el-form-item label="最大购买数量">
      <el-input-number v-model="maxBuy" :min="1" :step="1" :controls="false" />
    </el-form-item>
    <el-form-item v-if="deliveryType !== 'CARD'" label="充值字段" class="pricing-recharge">
      <el-select v-model="accountTypes" multiple collapse-tags collapse-tags-tooltip filterable placeholder="请选择充值字段">
        <el-option v-for="item in accountTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </el-form-item>
  </div>
  <div v-if="deliveryType === 'CARD'" class="rule-empty compact">卡密商品无需设置充值字段。</div>
</template>

<style scoped>
.pricing-compact {
  display: grid;
  grid-template-columns: minmax(180px, 1.18fr) minmax(104px, 0.58fr) repeat(4, minmax(88px, 0.42fr));
  gap: 8px;
  align-items: end;
}

.pricing-compact.withRecharge {
  grid-template-columns: minmax(168px, 1.05fr) minmax(96px, 0.48fr) repeat(4, minmax(80px, 0.36fr)) minmax(170px, 1fr);
}

.pricing-compact :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}

.pricing-compact :deep(.el-input-number),
.pricing-compact :deep(.el-select) {
  width: 100%;
}

.pricing-compact :deep(.el-input-number .el-input__wrapper) {
  padding-left: 8px;
}

.pricing-compact :deep(.el-input-number__decrease),
.pricing-compact :deep(.el-input-number__increase) {
  width: 26px;
}

.pricing-template {
  grid-column: span 1;
}

.pricing-status {
  min-width: 96px;
}

.pricing-recharge {
  min-width: 0;
}

.rule-empty.compact {
  margin-top: 8px;
  padding: 8px 10px;
  color: rgba(255, 255, 255, 0.48);
  font-size: 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.035);
  border: 0.5px dashed rgba(255, 255, 255, 0.13);
}

@media (max-width: 1260px) {
  .pricing-compact,
  .pricing-compact.withRecharge {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .pricing-compact {
    grid-template-columns: 1fr;
  }
}
</style>
