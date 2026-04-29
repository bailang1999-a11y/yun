<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { LoaderCircle, Search } from 'lucide-vue-next'
import AppTabbar from '../components/AppTabbar.vue'
import { useCatalogStore } from '../stores/catalog'
import type { GoodsCard, GoodsType } from '../types/h5'

const catalog = useCatalogStore()
const router = useRouter()

const typeLabel: Record<GoodsType, string> = {
  CARD: '卡密兑换',
  DIRECT: '自动充值',
  MANUAL: '人工充值'
}

const goods = computed(() => catalog.visibleGoods)
const progressWidth = computed(() => `${Math.max(catalog.depthProgress, catalog.activePath.length ? 0.18 : 0.06) * 100}%`)
const layerLabel = computed(() => (catalog.activePath.length ? `第 ${catalog.activePath.length + 1} 层类目` : '一级类目'))

onMounted(() => {
  void catalog.loadCatalog()
})

function openGoods(item: GoodsCard) {
  void router.push(`/goods/${item.id}`)
}

function stockTone(item: GoodsCard) {
  const label = item.stockLabel
  if (/售罄|缺货|排队/.test(label)) return 'out'
  if (/仅剩|紧张|告急|库存 0|库存 1|库存 2|库存 3/.test(label)) return 'low'
  return 'full'
}
</script>

<template>
  <main class="page">
    <header class="hero liquid-surface">
      <div>
        <p class="eyebrow">X-Platform-Code: {{ catalog.platformCode }}</p>
        <h1>喜易云</h1>
        <p>卡密自动发货、直充秒到账、代充可追踪。</p>
      </div>
    </header>

    <section class="page-pad">
      <form class="search-box liquid-surface" role="search" @submit.prevent="catalog.setSearchKeyword(catalog.searchKeyword)">
        <Search :size="18" aria-hidden="true" />
        <label class="sr-only" for="goodsSearch">搜索商品</label>
        <input id="goodsSearch" v-model.trim="catalog.searchKeyword" placeholder="搜索商品名称、面值或教程" />
        <button type="submit">搜索</button>
      </form>

      <div class="liquid-progress" aria-hidden="true">
        <span :style="{ width: progressWidth }" />
      </div>

      <div class="category-dock liquid-surface" aria-label="商品分类">
        <div class="fluid-breadcrumbs">
          <button type="button" :class="{ active: !catalog.activePath.length }" @click="catalog.resetCategory()">全部</button>
          <button
            v-for="(item, index) in catalog.activeTrail"
            :key="item.id"
            type="button"
            class="bubble"
            @click="catalog.goToDepth(index)"
          >
            {{ item.name }}
          </button>
        </div>
        <div class="category-meta">
          <span>{{ layerLabel }}</span>
          <small>{{ catalog.activePath.length }}/5</small>
        </div>
        <div class="category-row">
          <button
            v-for="item in catalog.currentCategories"
            :key="item.id"
            :class="{ active: catalog.activePath.includes(item.id) }"
            type="button"
            @click="catalog.selectCategory(item)"
          >
            {{ item.name }}
          </button>
        </div>
      </div>

      <section v-if="catalog.errorMessage" class="notice warn">
        {{ catalog.errorMessage }} 当前展示本地示例商品。
      </section>

      <div class="section-title">
        <span>可售商品</span>
        <small class="muted">{{ catalog.loading ? '加载中' : `${goods.length}/${catalog.total} 件` }}</small>
      </div>

      <section v-if="catalog.loading" class="loading">
        <LoaderCircle class="spin" :size="18" />
        正在加载商品
      </section>
      <section v-else-if="!goods.length" class="empty-state">暂无可售商品，请稍后再来。</section>

      <article v-for="item in goods" :key="item.id" class="goods-card liquid-surface" :data-stock="stockTone(item)">
        <div class="cover" :data-type="item.type">{{ item.cover }}</div>
        <div class="goods-main">
          <div class="goods-head">
            <h2>{{ item.name }}</h2>
            <span>{{ typeLabel[item.type] }}</span>
          </div>
          <p class="muted">{{ item.faceValue }} · {{ item.stockLabel }}</p>
          <div class="goods-foot">
            <strong class="metal-price">¥{{ item.price.toFixed(2) }}</strong>
            <del v-if="item.originalPrice">¥{{ item.originalPrice.toFixed(2) }}</del>
            <button type="button" @click="openGoods(item)">购买</button>
          </div>
        </div>
      </article>

      <button
        v-if="!catalog.loading && goods.length < catalog.total"
        class="load-more liquid-surface"
        type="button"
        :disabled="catalog.loadingMore"
        @click="catalog.loadMoreGoods()"
      >
        <LoaderCircle v-if="catalog.loadingMore" class="spin" :size="16" aria-hidden="true" />
        {{ catalog.loadingMore ? '正在加载' : '加载更多商品' }}
      </button>
    </section>

    <AppTabbar />
  </main>
</template>

<style scoped>
.hero {
  min-height: 184px;
  padding: 26px 18px;
  display: flex;
  align-items: flex-end;
  color: #fff;
  overflow: hidden;
  border-radius: 0 0 34px 34px;
  background:
    linear-gradient(135deg, rgba(7, 14, 27, 0.58), rgba(8, 67, 72, 0.3)),
    url("https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=1200&q=80") center/cover;
}

.hero h1 {
  margin: 4px 0 6px;
  font-size: 34px;
  line-height: 1.1;
}

.hero p {
  margin: 0;
  color: rgba(255, 255, 255, 0.86);
}

.eyebrow {
  font-size: 12px;
  letter-spacing: 0;
}

.search-box {
  height: 44px;
  padding: 0 14px;
  display: grid;
  grid-template-columns: 20px 1fr auto;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.58);
  border-radius: 18px;
}

.search-box input {
  min-width: 0;
  color: rgba(255, 255, 255, 0.86);
  border: 0;
  outline: 0;
  background: transparent;
}

.search-box input::placeholder {
  color: rgba(255, 255, 255, 0.48);
}

.search-box button {
  height: 30px;
  padding: 0 12px;
  color: #06100e;
  border: 0;
  border-radius: 999px;
  background: #00ffc3;
  font-size: 13px;
  font-weight: 800;
}

.search-box button:active {
  transform: scale(0.96);
}

.liquid-progress {
  position: relative;
  height: 10px;
  margin: 14px 2px 0;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.045);
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(20px);
}

.liquid-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, rgba(0, 255, 195, 0.28), rgba(88, 166, 255, 0.75), rgba(255, 255, 255, 0.88));
  box-shadow: 0 0 24px rgba(0, 255, 195, 0.36);
  transition: width 520ms cubic-bezier(0.2, 0.85, 0.2, 1);
}

.category-dock {
  margin-top: 12px;
  padding: 8px;
  border-radius: 24px;
}

.fluid-breadcrumbs {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  padding: 2px 2px 8px;
}

.fluid-breadcrumbs button {
  flex: 0 0 auto;
  height: 28px;
  padding: 0 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.52);
  background: rgba(255, 255, 255, 0.04);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(18px) saturate(180%);
  transition: transform 180ms ease, color 180ms ease, background 180ms ease;
}

.fluid-breadcrumbs button:active {
  transform: scale(0.96);
}

.fluid-breadcrumbs .active,
.fluid-breadcrumbs .bubble {
  color: rgba(255, 255, 255, 0.88);
  background: rgba(255, 255, 255, 0.08);
}

.fluid-breadcrumbs .bubble {
  max-width: 118px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 12px;
}

.category-meta small {
  color: rgba(0, 255, 195, 0.72);
}

.category-row {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 0;
}

.category-row button {
  flex: 0 0 auto;
  height: 38px;
  padding: 0 14px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.58);
  background: rgba(255, 255, 255, 0.045);
  transition: transform 180ms ease, color 180ms ease, background 180ms ease, box-shadow 180ms ease;
  backdrop-filter: blur(18px);
}

.category-row button:active {
  transform: scale(0.98);
}

.category-row .active {
  color: #fff;
  border-color: rgba(0, 255, 195, 0.36);
  background: rgba(0, 255, 195, 0.12);
  box-shadow: 0 0 34px rgba(0, 255, 195, 0.18);
}

.goods-card {
  position: relative;
  display: grid;
  grid-template-columns: 82px 1fr;
  gap: 12px;
  padding: 14px;
  margin-bottom: 12px;
  overflow: hidden;
  border-radius: 26px;
  transition: transform 180ms ease, filter 180ms ease, box-shadow 180ms ease;
}

.goods-card:active {
  transform: scale(0.985);
}

.goods-card::before {
  content: "";
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 1;
  width: 9px;
  height: 9px;
  border-radius: 999px;
}

.goods-card[data-stock="full"] {
  box-shadow: 0 0 42px rgba(0, 255, 195, 0.14);
}

.goods-card[data-stock="full"]::before {
  background: #00ffc3;
  box-shadow: 0 0 20px #00ffc3;
}

.goods-card[data-stock="low"] {
  animation: amber-pulse 1.8s ease-in-out infinite;
}

.goods-card[data-stock="low"]::before {
  background: #ffab00;
  box-shadow: 0 0 20px #ffab00;
}

.goods-card[data-stock="out"] {
  filter: grayscale(0.8) saturate(0.6);
  opacity: 0.7;
}

.goods-card[data-stock="out"]::before {
  background: rgba(255, 255, 255, 0.38);
}

.cover {
  width: 82px;
  height: 82px;
  border-radius: 24px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.68), rgba(10, 77, 80, 0.82));
  box-shadow: inset 0 1px 18px rgba(255, 255, 255, 0.2), 0 14px 30px rgba(0, 0, 0, 0.22);
  backdrop-filter: blur(24px);
}

.cover[data-type="DIRECT"] {
  background: linear-gradient(135deg, rgba(88, 166, 255, 0.76), rgba(37, 99, 235, 0.48));
}

.cover[data-type="MANUAL"] {
  background: linear-gradient(135deg, rgba(255, 171, 0, 0.7), rgba(154, 91, 19, 0.5));
}

.goods-main {
  min-width: 0;
}

.goods-head {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
}

.goods-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 15px;
  line-height: 1.35;
  font-weight: 400;
  transition: font-weight 180ms ease;
}

.goods-card:focus-within .goods-head h2,
.goods-card:hover .goods-head h2 {
  font-weight: 600;
}

.goods-head span {
  flex: 0 0 auto;
  padding: 3px 6px;
  border-radius: 4px;
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  font-size: 11px;
}

.goods-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.goods-foot strong {
  font-size: 26px;
  line-height: 1;
}

.goods-foot del {
  color: rgba(255, 255, 255, 0.35);
  font-size: 12px;
}

.goods-foot button {
  margin-left: auto;
  height: 36px;
  min-width: 74px;
  padding: 0 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 14px 34px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(22px);
  transition: transform 180ms ease;
}

.goods-foot button:active {
  transform: scale(0.96);
}

.goods-foot button:disabled {
  opacity: 0.68;
}

.load-more {
  width: 100%;
  height: 44px;
  margin: 4px 0 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.82);
  border-radius: 999px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
}

.load-more:disabled {
  opacity: 0.66;
}

.notice,
.loading,
.empty-state {
  margin-top: 12px;
  padding: 12px;
  border-radius: 18px;
  font-size: 13px;
  background: rgba(255, 255, 255, 0.055);
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(24px);
}

.notice.warn {
  color: #ffcf72;
}

.notice.danger {
  color: #ff8d86;
}

.notice.success {
  color: #00ffc3;
}

.loading,
.empty-state {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.58);
}

.spin,
.blue-swirl {
  animation: spin 0.9s linear infinite;
}

.blue-swirl {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  border: 2px solid rgba(88, 166, 255, 0.28);
  border-top-color: #58a6ff;
  box-shadow: 0 0 18px rgba(88, 166, 255, 0.55);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
