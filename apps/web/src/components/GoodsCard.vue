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
        <span v-for="spec in visibleTags.specs" :key="`spec-${spec}`" class="goods-spec-chip" :style="tokenStyle('spec', spec)">{{ spec }}</span>
      </div>
      <div v-if="visibleTags.sourceChannels.length" class="goods-source-row" aria-label="货源渠道">
        <span v-for="channel in visibleTags.sourceChannels" :key="`source-${channel}`" class="goods-tag tag-source" :style="tokenStyle('source', channel)">{{ channel }}</span>
      </div>
      <div class="goods-tags" aria-label="商品标签">
        <span v-for="tag in visibleTags.custom" :key="`custom-${tag}`" class="goods-tag tag-custom" :style="tokenStyle('custom', tag)">{{ tag }}</span>
        <span v-for="platform in visibleTags.salePlatforms" :key="`sale-${platform}`" class="goods-tag tag-sale">
          <i class="platform-icon" :data-platform="platformKey(platform)" aria-hidden="true">{{ platformIcon(platform) }}</i>
          <span>{{ platformLabel(platform) }}</span>
        </span>
      </div>
    </div>

    <div class="goods-purchase">
      <div v-if="visibleTags.alerts.length || visibleTags.forbiddenPlatforms.length" class="goods-side-section goods-alert-section">
        <span class="goods-side-title danger">限制提示</span>
        <div class="goods-side-chips">
          <span v-for="alert in visibleTags.alerts" :key="`alert-${alert}`" class="goods-alert-chip">{{ alert }}</span>
          <span v-for="platform in visibleTags.forbiddenPlatforms" :key="`deny-${platform}`" class="goods-alert-chip tag-deny">
            <i class="platform-icon" :data-platform="platformKey(platform)" aria-hidden="true">{{ platformIcon(platform) }}</i>
            <span>禁 {{ platformLabel(platform) }}</span>
          </span>
        </div>
      </div>
      <div class="goods-buy-panel">
        <span class="goods-stock">{{ goods.stockLabel }}</span>
        <div class="goods-price-stack">
          <small>采购价</small>
          <strong>¥{{ formatMoney(goods.price) }}</strong>
          <del v-if="goods.originalPrice">¥{{ formatMoney(goods.originalPrice) }}</del>
        </div>
        <b :class="{ disabled: !goods.canBuy }" :aria-disabled="!goods.canBuy">{{ goods.canBuy ? '立即购买' : restrictionLabel }}</b>
      </div>
    </div>

    <Teleport to="body">
      <div
        v-if="restrictionDialogMessage"
        ref="restrictionDialogRef"
        class="limit-dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="`goods-restriction-title-${goods.id}`"
        tabindex="-1"
        @click.stop
      >
        <div class="limit-dialog-card">
          <span>下单限制</span>
          <strong :id="`goods-restriction-title-${goods.id}`">暂无法购买该商品</strong>
          <p>{{ restrictionDialogMessage }}</p>
          <button type="button" @click.stop="closeRestrictionDialog">我知道了</button>
        </div>
      </div>
    </Teleport>
  </RouterLink>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { StyleValue } from 'vue'
import { RouterLink } from 'vue-router'
import type { GoodsItem } from '../types/web'
import { formatMoney } from '../utils/formatters'
import { useModalFocus } from '../utils/modalFocus'

const props = defineProps<{ goods: GoodsItem }>()
const restrictionDialogMessage = ref('')
const restrictionDialogRef = ref<HTMLElement | null>(null)
useModalFocus(computed(() => Boolean(restrictionDialogMessage.value)), restrictionDialogRef, closeRestrictionDialog)

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
  forbiddenPlatforms: (props.goods.forbiddenPlatforms || []).slice(0, 2),
  specs: [
    ...(props.goods.benefitDurations || []).slice(0, 2),
    props.goods.benefitType,
    props.goods.benefitBrand
  ].filter(Boolean) as string[],
  alerts: [
    props.goods.priceLimitText ? `限价 ${props.goods.priceLimitText}` : ''
  ].filter(Boolean)
}))

function handleOpen(event: MouseEvent) {
  if (props.goods.canBuy) return
  event.preventDefault()
  restrictionDialogMessage.value = props.goods.buyRestrictionReason || '该商品当前暂无法购买。'
}

function closeRestrictionDialog() {
  restrictionDialogMessage.value = ''
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

function platformIcon(value: string) {
  const icons: Record<string, string> = {
    douyin: '抖',
    taobao: '淘',
    pdd: '拼',
    xianyu: '闲',
    xiaohongshu: '书',
    private: '私',
    h5: 'H5',
    web: 'W',
    pc: 'PC',
    api: 'API',
    all: '全'
  }
  return icons[value] || platformLabel(value).slice(0, 1)
}

function platformKey(value: string) {
  return value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, '')
}

const tokenPalettes: Record<string, Array<[string, string, string, string]>> = {
  spec: [
    ['oklch(96% 0.07 214 / 0.94)', 'oklch(81% 0.12 214 / 0.78)', 'oklch(36% 0.13 224)', 'oklch(65% 0.14 224 / 0.62)'],
    ['oklch(96% 0.08 172 / 0.94)', 'oklch(82% 0.12 172 / 0.76)', 'oklch(32% 0.12 172)', 'oklch(61% 0.14 172 / 0.62)'],
    ['oklch(96% 0.07 292 / 0.94)', 'oklch(83% 0.11 292 / 0.76)', 'oklch(37% 0.14 292)', 'oklch(64% 0.14 292 / 0.62)'],
    ['oklch(96% 0.08 78 / 0.94)', 'oklch(84% 0.13 78 / 0.76)', 'oklch(37% 0.12 78)', 'oklch(66% 0.15 78 / 0.62)'],
    ['oklch(96% 0.07 24 / 0.94)', 'oklch(84% 0.11 24 / 0.76)', 'oklch(38% 0.14 24)', 'oklch(66% 0.16 24 / 0.62)']
  ],
  source: [
    ['oklch(94% 0.065 190 / 0.94)', 'oklch(82% 0.1 190 / 0.76)', 'oklch(31% 0.12 190)', 'oklch(60% 0.13 190 / 0.62)'],
    ['oklch(94% 0.065 236 / 0.94)', 'oklch(82% 0.1 236 / 0.76)', 'oklch(31% 0.13 236)', 'oklch(60% 0.14 236 / 0.62)'],
    ['oklch(94% 0.07 150 / 0.94)', 'oklch(82% 0.11 150 / 0.76)', 'oklch(31% 0.12 150)', 'oklch(60% 0.14 150 / 0.62)']
  ],
  custom: [
    ['oklch(94% 0.065 252 / 0.94)', 'oklch(82% 0.1 252 / 0.76)', 'oklch(32% 0.12 252)', 'oklch(61% 0.14 252 / 0.62)'],
    ['oklch(94% 0.065 320 / 0.94)', 'oklch(82% 0.1 320 / 0.76)', 'oklch(36% 0.13 320)', 'oklch(64% 0.14 320 / 0.62)'],
    ['oklch(94% 0.07 118 / 0.94)', 'oklch(82% 0.11 118 / 0.76)', 'oklch(34% 0.12 118)', 'oklch(62% 0.14 118 / 0.62)']
  ]
}

function tokenStyle(group: keyof typeof tokenPalettes, value: string): StyleValue {
  const palette = tokenPalettes[group]
  const [start, end, ink, border] = palette[tokenHash(`${group}:${value}`) % palette.length]
  return {
    '--tag-bg-start': start,
    '--tag-bg-end': end,
    '--tag-ink': ink,
    '--tag-border': border
  }
}

function tokenHash(value: string) {
  let hash = 0
  for (const char of value.trim().toLowerCase()) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  }
  return hash
}
</script>
