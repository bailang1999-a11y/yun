<template>
  <RouterLink class="goods-card" :to="`/goods/${goods.id}`">
    <div class="goods-cover">
      <span>{{ goods.cover }}</span>
      <em>{{ typeText }}</em>
    </div>
    <div class="goods-body">
      <div class="goods-meta">
        <span>{{ goods.category }}</span>
        <span>{{ goods.stockLabel }}</span>
      </div>
      <h3>{{ goods.name }}</h3>
      <p>{{ goods.faceValue }}</p>
      <div class="goods-bottom">
        <span>
          <small>采购价</small>
          <strong>¥{{ goods.price.toFixed(2) }}</strong>
        </span>
        <b>立即购买</b>
      </div>
    </div>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { GoodsItem } from '../types/web'

const props = defineProps<{ goods: GoodsItem }>()

const typeText = computed(() => {
  if (props.goods.type === 'DIRECT') return '直充'
  if (props.goods.type === 'MANUAL') return '代充'
  return '卡密'
})
</script>
