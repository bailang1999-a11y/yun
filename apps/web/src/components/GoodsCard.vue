<template>
  <RouterLink class="goods-card" :class="{ 'is-restricted': !goods.canBuy }" :to="`/goods/${goods.id}`" @click="handleOpen">
    <div class="goods-media">
      <div class="goods-cover">
        <img v-if="goods.coverUrl" :src="goods.coverUrl" :alt="goods.name" loading="lazy" />
        <span v-else>{{ goods.cover }}</span>
        <em class="goods-type-badge">{{ typeText }}</em>
      </div>
    </div>

    <div class="goods-info">
      <div class="goods-title-block">
        <h3>{{ goods.name }}</h3>
        <p>{{ goods.faceValue }}</p>
      </div>
      <div v-if="visibleTags.specs.length" class="goods-spec-row" aria-label="商品规格">
        <span v-for="spec in visibleTags.specs" :key="`spec-${spec}`" class="goods-spec-chip">{{ spec }}</span>
      </div>
      <div v-if="visibleTags.sourceChannels.length" class="goods-source-row" aria-label="货源渠道">
        <span v-for="channel in visibleTags.sourceChannels" :key="`source-${channel}`" class="goods-tag tag-source">{{ channel }}</span>
      </div>
      <div class="goods-tags" aria-label="商品标签">
        <span v-for="tag in visibleTags.custom" :key="`custom-${tag}`" class="goods-tag tag-custom">{{ tag }}</span>
        <span v-for="platform in visibleTags.salePlatforms" :key="`sale-${platform}`" class="goods-tag tag-sale">
          {{ platformLabel(platform) }}
        </span>
      </div>
    </div>

    <div class="goods-purchase">
      <div v-if="visibleTags.alerts.length" class="goods-side-section goods-alert-section">
        <span class="goods-side-title danger">限制提示</span>
        <div class="goods-side-chips">
          <span v-for="alert in visibleTags.alerts" :key="`alert-${alert}`" class="goods-alert-chip">{{ alert }}</span>
        </div>
      </div>
      <div class="goods-buy-panel">
        <span class="goods-stock">{{ goods.stockLabel }}</span>
        <div class="goods-price-stack">
          <small>采购价</small>
          <strong>¥{{ goods.price.toFixed(2) }}</strong>
          <del v-if="goods.originalPrice">¥{{ goods.originalPrice.toFixed(2) }}</del>
        </div>
        <b :class="{ disabled: !goods.canBuy }" :aria-disabled="!goods.canBuy">{{ goods.canBuy ? '立即购买' : restrictionLabel }}</b>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="restrictionDialogMessage" class="limit-dialog" role="dialog" aria-modal="true" @click.stop>
        <div class="limit-dialog-card">
          <span>下单限制</span>
          <strong>暂无法购买该商品</strong>
          <p>{{ restrictionDialogMessage }}</p>
          <button type="button" @click.stop="restrictionDialogMessage = ''">我知道了</button>
        </div>
      </div>
    </Teleport>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { GoodsItem } from '../types/web'

const props = defineProps<{ goods: GoodsItem }>()
const restrictionDialogMessage = ref('')

const typeText = computed(() => {
  if (props.goods.type === 'DIRECT') return '直充'
  if (props.goods.type === 'MANUAL') return '代充'
  return '卡密'
})

const restrictionLabel = computed(() => {
  if (props.goods.soldOut) return '已售罄'
  if (props.goods.buyRestrictionReason) return '暂不可买'
  return '受限'
})

const visibleTags = computed(() => ({
  custom: (props.goods.tags || []).slice(0, 2),
  sourceChannels: (props.goods.sourceChannels || []).slice(0, 3),
  salePlatforms: (props.goods.availablePlatforms || []).slice(0, 3),
  specs: [
    ...(props.goods.benefitDurations || []).slice(0, 2),
    props.goods.benefitType,
    props.goods.benefitBrand
  ].filter(Boolean) as string[],
  alerts: [
    props.goods.priceLimitText ? `限价 ${props.goods.priceLimitText}` : '',
    ...(props.goods.forbiddenPlatforms || []).slice(0, 2).map((platform) => `禁 ${platformLabel(platform)}`)
  ].filter(Boolean)
}))

function handleOpen(event: MouseEvent) {
  if (props.goods.canBuy) return
  event.preventDefault()
  restrictionDialogMessage.value = props.goods.buyRestrictionReason || '该商品当前暂无法购买。'
}

function platformLabel(value: string) {
  const labels: Record<string, string> = {
    douyin: '抖音',
    taobao: '淘宝',
    pdd: '拼多多',
    xianyu: '咸鱼',
    xiaohongshu: '小红书',
    private: '私域',
    h5: 'H5',
    web: 'Web',
    pc: 'PC',
    api: 'API'
  }
  return labels[value] || value
}
</script>
