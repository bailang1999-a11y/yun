<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
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

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void catalog.loadCatalog()
}

function refreshCatalog() {
  void catalog.loadCatalog()
}

onMounted(() => {
  refreshCatalog()
  window.addEventListener('focus', refreshCatalog)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshCatalog)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})

function openGoods(item: GoodsCard) {
  void router.push(`/goods/${item.id}`)
}

function stockTone(item: GoodsCard) {
  if (item.soldOut || !item.canBuy) return 'out'
  const label = item.stockLabel
  if (/售罄|缺货|排队|库存 0/.test(label)) return 'out'
  if (/仅剩|紧张|告急|库存 0|库存 1|库存 2|库存 3/.test(label)) return 'low'
  return 'full'
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

<template>
  <main class="page">
    <header class="hero liquid-surface">
      <div>
        <p class="eyebrow">实时同步后台商品</p>
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
        {{ catalog.errorMessage }} 请稍后重试。
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
        <div class="cover" :data-type="item.type">
          <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.name" loading="lazy" />
          <span v-else>{{ item.cover }}</span>
        </div>
        <div class="goods-main">
          <div class="goods-head">
            <h2>{{ item.name }}</h2>
            <span>{{ typeLabel[item.type] }}</span>
          </div>
          <p class="muted">{{ item.faceValue }} · {{ item.stockLabel }}</p>
          <div class="goods-tags">
            <span v-for="tag in item.tags || []" :key="`custom-${tag}`" class="tag tag-custom">{{ tag }}</span>
            <span v-for="duration in item.benefitDurations || []" :key="`duration-${duration}`" class="tag tag-time">{{ duration }}</span>
            <span v-if="item.benefitType" class="tag tag-type">{{ item.benefitType }}</span>
            <span v-if="item.benefitBrand" class="tag tag-brand">{{ item.benefitBrand }}</span>
            <span v-if="item.priceLimitText" class="tag tag-limit">限价 {{ item.priceLimitText }}</span>
            <span v-for="platform in item.availablePlatforms || []" :key="`sale-${platform}`" class="tag tag-sale">
              {{ platformLabel(platform) }}
            </span>
            <span v-for="platform in item.forbiddenPlatforms || []" :key="`deny-${platform}`" class="tag tag-deny">
              禁 {{ platformLabel(platform) }}
            </span>
            <span v-if="item.soldOut" class="tag tag-deny">已售罄</span>
          </div>
          <div class="goods-foot">
            <strong class="metal-price">¥{{ item.price.toFixed(2) }}</strong>
            <del v-if="item.originalPrice">¥{{ item.originalPrice.toFixed(2) }}</del>
            <button type="button" :disabled="!item.canBuy" @click="openGoods(item)">
              {{ item.canBuy ? '购买' : '暂不可买' }}
            </button>
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
  position: relative;
  min-height: 184px;
  padding: 26px 18px;
  display: flex;
  align-items: flex-end;
  color: #f8fbff;
  overflow: hidden;
  border-radius: 0 0 34px 34px;
  background:
    linear-gradient(135deg, rgba(7, 14, 27, 0.96), rgba(8, 67, 72, 0.72)),
    repeating-linear-gradient(112deg, rgba(255, 255, 255, 0.11) 0 1px, transparent 1px 18px),
    linear-gradient(145deg, #08111f, #0a3436 58%, #07111a);
}

.hero::before,
.hero::after {
  content: "";
  position: absolute;
  pointer-events: none;
}

.hero::before {
  width: 168px;
  height: 108px;
  right: -18px;
  bottom: 22px;
  border: 1px solid rgba(215, 255, 246, 0.2);
  border-radius: 22px;
  background:
    linear-gradient(90deg, rgba(215, 255, 246, 0.08) 0 30%, transparent 30%),
    linear-gradient(180deg, rgba(0, 255, 195, 0.2), rgba(88, 166, 255, 0.08));
  box-shadow: -26px 20px 0 rgba(255, 255, 255, 0.045), 0 22px 48px rgba(0, 0, 0, 0.28);
  transform: rotate(-8deg);
}

.hero::after {
  width: 86px;
  height: 86px;
  right: 84px;
  bottom: -22px;
  border: 1px solid rgba(215, 255, 246, 0.18);
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.16), transparent 42%),
    rgba(0, 255, 195, 0.09);
  transform: rotate(14deg);
}

.hero > div {
  position: relative;
  z-index: 1;
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
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px;
  min-height: 112px;
  padding: 9px;
  margin-bottom: 9px;
  overflow: hidden;
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.085), rgba(255, 255, 255, 0.035)),
    rgba(255, 255, 255, 0.05);
  box-shadow: 0 14px 34px rgba(1, 8, 18, 0.2);
  transition: transform 180ms ease, filter 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.goods-card:active {
  transform: scale(0.985);
}

.goods-card::before {
  content: "";
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
  width: 7px;
  height: 7px;
  border-radius: 999px;
}

.goods-card[data-stock="full"] {
  box-shadow: 0 14px 34px rgba(1, 8, 18, 0.2), 0 0 28px rgba(0, 255, 195, 0.08);
}

.goods-card[data-stock="full"]::before {
  background: #00ffc3;
  box-shadow: 0 0 14px #00ffc3;
}

.goods-card[data-stock="low"] {
  box-shadow: 0 14px 34px rgba(1, 8, 18, 0.2), 0 0 24px rgba(255, 171, 0, 0.12);
}

.goods-card[data-stock="low"]::before {
  background: #ffab00;
  box-shadow: 0 0 14px #ffab00;
}

.goods-card[data-stock="out"] {
  filter: grayscale(0.8) saturate(0.6);
  opacity: 0.7;
}

.goods-card[data-stock="out"]::before {
  background: rgba(255, 255, 255, 0.38);
}

.cover {
  width: 92px;
  height: 92px;
  align-self: center;
  border-radius: 13px;
  position: relative;
  overflow: hidden;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, rgba(0, 255, 195, 0.68), rgba(10, 77, 80, 0.82));
  box-shadow: inset 0 1px 16px rgba(255, 255, 255, 0.18), 0 10px 22px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(24px);
}

.cover img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover span {
  position: relative;
  z-index: 1;
}

.cover[data-type="DIRECT"] {
  background: linear-gradient(135deg, rgba(88, 166, 255, 0.76), rgba(37, 99, 235, 0.48));
}

.cover[data-type="MANUAL"] {
  background: linear-gradient(135deg, rgba(255, 171, 0, 0.7), rgba(154, 91, 19, 0.5));
}

.goods-main {
  min-width: 0;
  display: grid;
  align-content: center;
}

.goods-head {
  display: flex;
  gap: 7px;
  align-items: flex-start;
  justify-content: space-between;
  padding-right: 11px;
}

.goods-head h2 {
  margin: 0;
  color: rgba(255, 255, 255, 0.92);
  font-size: 14px;
  line-height: 1.32;
  font-weight: 650;
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  transition: font-weight 180ms ease;
}

.goods-card:focus-within .goods-head h2,
.goods-card:hover .goods-head h2 {
  color: rgba(255, 255, 255, 0.98);
}

.goods-head span {
  flex: 0 0 auto;
  padding: 2px 6px;
  border-radius: 4px;
  color: #00ffc3;
  background: rgba(0, 255, 195, 0.1);
  border: 0.5px solid rgba(0, 255, 195, 0.16);
  font-size: 10px;
  line-height: 1.3;
}

.goods-main > .muted {
  margin: 3px 0 0;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 42px;
  margin-top: 6px;
  overflow: hidden;
}

.tag {
  min-height: 19px;
  display: inline-flex;
  align-items: center;
  max-width: 82px;
  padding: 2px 6px;
  overflow: hidden;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 750;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0.5px solid rgba(255, 255, 255, 0.14);
}

.tag-time {
  color: #c9fff4;
  background: rgba(0, 214, 178, 0.14);
  border-color: rgba(0, 229, 190, 0.28);
}

.tag-type {
  color: #e4ddff;
  background: rgba(122, 92, 255, 0.16);
  border-color: rgba(148, 121, 255, 0.3);
}

.tag-brand {
  color: #d6f0ff;
  background: rgba(46, 152, 235, 0.15);
  border-color: rgba(84, 180, 255, 0.3);
}

.tag-custom {
  color: #e6fbff;
  background: rgba(20, 184, 166, 0.15);
  border-color: rgba(45, 212, 191, 0.3);
}

.tag-limit {
  color: #fff3d2;
  background: rgba(245, 158, 11, 0.18);
  border-color: rgba(251, 191, 36, 0.34);
}

.tag-sale {
  color: #eaf7ff;
  background: rgba(68, 134, 255, 0.16);
  border-color: rgba(106, 164, 255, 0.32);
}

.tag-deny {
  color: #ffe1e1;
  background: rgba(236, 77, 93, 0.14);
  border-color: rgba(255, 112, 126, 0.3);
}

.goods-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px;
}

.goods-foot strong {
  font-size: 21px;
  line-height: 1;
}

.goods-foot del {
  color: rgba(255, 255, 255, 0.35);
  font-size: 12px;
}

.goods-foot button {
  margin-left: auto;
  height: 30px;
  min-width: 64px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  border: 0.5px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(88, 166, 255, 0.86), rgba(122, 92, 255, 0.78));
  box-shadow: 0 10px 24px rgba(64, 82, 220, 0.28);
  backdrop-filter: blur(22px);
  font-size: 12px;
  font-weight: 800;
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
