<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  BadgeIcon,
  BadgePercent,
  BookOpen,
  BriefcaseBusiness,
  Check,
  Clapperboard,
  Film,
  Flame,
  FolderTree,
  Gamepad2,
  Headset,
  HeartHandshake,
  Layers3,
  LayoutGrid,
  LoaderCircle,
  MonitorCog,
  Rocket,
  Search,
  ShoppingBag,
  Smartphone,
  Ticket
} from 'lucide-vue-next'
import AppTabbar from '../components/AppTabbar.vue'
import { useCatalogStore } from '../stores/catalog'
import type { GoodsCard, GoodsType, H5Category } from '../types/h5'
import { formatMoney } from '../utils/formatters'

const catalog = useCatalogStore()
const router = useRouter()

const typeLabel: Record<GoodsType, string> = {
  CARD: '卡密兑换',
  DIRECT: '自动充值',
  MANUAL: '人工充值'
}

const backendIconMap = {
  badge: BadgeIcon,
  film: Film,
  gamepad: Gamepad2,
  business: BriefcaseBusiness,
  rocket: Rocket,
  flame: Flame,
  book: BookOpen,
  bag: ShoppingBag,
  heart: HeartHandshake,
  monitor: MonitorCog
}

const categoryIconRules = [
  { pattern: /影视|视频|影音|电影|音乐/, icon: Clapperboard },
  { pattern: /游戏|网游|手游|点券/, icon: Gamepad2 },
  { pattern: /人工|客服|服务/, icon: Headset },
  { pattern: /话费|流量|手机|充值/, icon: Smartphone },
  { pattern: /数字|权益|会员|卡密|优惠|折扣/, icon: Ticket },
  { pattern: /福利|活动|特惠/, icon: BadgePercent }
]

const goods = computed(() => catalog.visibleGoods)
const categoryImageErrors = ref(new Set<string>())
const previewCategoryIconUrls = [
  '/category-icons/preview/kugou-normalized.png',
  '/category-icons/preview/iqiyi.png',
  '/category-icons/preview/bawang.png',
  '/category-icons/preview/baidupan.png',
  '/category-icons/preview/baiduwenku.png',
  '/category-icons/preview/baiguoyuan.png',
  '/category-icons/preview/beilehu.png',
  '/category-icons/preview/bixin.png',
  '/category-icons/preview/pizzahut.png',
  '/category-icons/preview/bilibili.png',
  '/category-icons/preview/chabaidao.png',
  '/category-icons/preview/chuangkete.png'
]
const previewCategoryImageById = computed(() => {
  if (!import.meta.env.DEV) return new Map<string, string>()
  return new Map(
    catalog.currentCategories.map((item, index) => [item.id, previewCategoryIconUrls[index % previewCategoryIconUrls.length]])
  )
})
const categoryPathLabel = computed(() => (
  catalog.activeTrail.length ? catalog.activeTrail.map((item) => item.name).join(' / ') : '全部商品'
))
const categoryLayerLabel = computed(() => {
  const level = catalog.currentCategories[0]?.level || Math.min(catalog.activePath.length + 1, 5)
  return ['一级分类', '二级分类', '三级分类', '四级分类', '五级分类'][Math.max(level - 1, 0)] || '商品分类'
})
const categoryScrollable = computed(() => catalog.currentCategories.length > 8)

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

function isImageUrl(value?: string) {
  return Boolean(value && /^(data:image\/|https?:\/\/|\/)/i.test(value))
}

function categoryImageUrl(item: H5Category) {
  if (categoryImageErrors.value.has(item.id)) return ''
  const previewIcon = previewCategoryImageById.value.get(item.id) || ''
  return [previewIcon, item.customIconUrl, item.iconUrl, isImageUrl(item.icon) ? item.icon : ''].find(Boolean) || ''
}

function categoryIcon(item: H5Category) {
  const savedIcon = item.icon?.trim().toLowerCase()
  if (savedIcon && !isImageUrl(savedIcon) && savedIcon in backendIconMap) {
    return backendIconMap[savedIcon as keyof typeof backendIconMap]
  }
  return categoryIconRules.find((rule) => rule.pattern.test(item.name))?.icon || FolderTree
}

function handleCategoryImageError(item: H5Category) {
  categoryImageErrors.value = new Set([...categoryImageErrors.value, item.id])
}

function isLongCategoryName(name: string) {
  return Array.from(name.trim()).length > 4
}

function goBackCategory() {
  if (catalog.activePath.length <= 1) {
    catalog.resetCategory()
    return
  }
  catalog.goToDepth(catalog.activePath.length - 2)
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
        <input id="goodsSearch" v-model.trim="catalog.searchKeyword" placeholder="商品名称 / 系统商品 ID / 面值" />
        <button type="submit">搜索</button>
      </form>

      <section class="category-dock liquid-surface" aria-labelledby="category-title">
        <div class="category-toolbar">
          <button
            v-if="catalog.activePath.length"
            class="category-back"
            type="button"
            aria-label="返回上一级分类"
            @click="goBackCategory"
          >
            <ArrowLeft :size="18" aria-hidden="true" />
          </button>
          <div class="category-heading">
            <div class="category-title-row">
              <div class="category-heading-main">
                <Layers3 :size="17" aria-hidden="true" />
                <h2 id="category-title">商品分类</h2>
              </div>
              <div class="category-meta">
                <span>{{ categoryLayerLabel }}</span>
                <small>{{ catalog.currentCategories.length }} 项</small>
              </div>
            </div>
            <p :title="categoryPathLabel">{{ categoryPathLabel }}</p>
          </div>
          <button
            v-if="catalog.activePath.length"
            class="category-reset"
            type="button"
            @click="catalog.resetCategory()"
          >
            <LayoutGrid :size="15" aria-hidden="true" />
            全部
          </button>
        </div>
        <div class="category-grid" :class="{ 'category-grid--scroll': categoryScrollable }">
          <button
            v-for="item in catalog.currentCategories"
            :key="item.id"
            :class="{ active: catalog.activePath.at(-1) === item.id }"
            :aria-current="catalog.activePath.at(-1) === item.id ? 'true' : undefined"
            :aria-label="item.name"
            :title="item.name"
            type="button"
            @click="catalog.selectCategory(item)"
          >
            <span class="category-icon-bubble" :class="{ 'has-image': categoryImageUrl(item) }">
              <img
                v-if="categoryImageUrl(item)"
                :src="categoryImageUrl(item)"
                :alt="`${item.name}图标`"
                @error="handleCategoryImageError(item)"
              />
              <component v-else :is="categoryIcon(item)" class="category-icon" :size="38" aria-hidden="true" />
            </span>
            <span class="category-name" :class="{ scrolling: isLongCategoryName(item.name) }">
              <span class="category-name-track">
                <span class="category-name-copy">{{ item.name }}</span>
                <span v-if="isLongCategoryName(item.name)" class="category-name-copy" aria-hidden="true">{{ item.name }}</span>
              </span>
            </span>
            <Check
              v-if="catalog.activePath.at(-1) === item.id"
              class="category-state-icon"
              :size="15"
              aria-hidden="true"
            />
          </button>
        </div>
      </section>

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

      <section v-else class="goods-list">
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
              <strong class="metal-price">¥{{ formatMoney(item.price) }}</strong>
              <del v-if="item.originalPrice">¥{{ formatMoney(item.originalPrice) }}</del>
              <button type="button" :disabled="!item.canBuy" @click="openGoods(item)">
                {{ item.canBuy ? '购买' : '暂不可买' }}
              </button>
            </div>
          </div>
        </article>
      </section>

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

.category-dock {
  margin-top: 12px;
  padding: 13px;
  border-radius: 18px;
}

.category-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
}

.category-back {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  color: rgba(255, 255, 255, 0.82);
  background: rgba(255, 255, 255, 0.055);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition: transform 180ms cubic-bezier(0.22, 1, 0.36, 1), background 180ms ease;
}

.category-back:active,
.category-reset:active,
.category-grid button:active {
  transform: scale(0.96);
}

.category-heading {
  flex: 1;
  min-width: 0;
}

.category-title-row,
.category-heading-main {
  display: flex;
  align-items: center;
  gap: 7px;
}

.category-title-row {
  gap: 8px;
  min-width: 0;
}

.category-heading-main {
  min-width: 0;
  color: rgba(255, 255, 255, 0.92);
}

.category-heading h2 {
  margin: 0;
  font-size: 15px;
  line-height: 1.25;
}

.category-heading p {
  margin: 4px 0 0;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.46);
  font-size: 11px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-reset {
  min-width: 48px;
  height: 36px;
  padding: 0 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 0.5px solid rgba(0, 255, 195, 0.22);
  border-radius: 10px;
  color: #c9fff3;
  background: rgba(0, 255, 195, 0.08);
  font-size: 12px;
  font-weight: 700;
  transition: transform 180ms cubic-bezier(0.22, 1, 0.36, 1), background 180ms ease;
}

.category-meta {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 5px;
  color: rgba(255, 255, 255, 0.52);
  font-size: 10px;
  white-space: nowrap;
}

.category-meta small {
  color: rgba(0, 255, 195, 0.72);
}

.category-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
}

.category-grid--scroll {
  grid-template-columns: unset;
  grid-template-rows: repeat(2, auto);
  grid-auto-flow: column;
  grid-auto-columns: 68px;
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 4px;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}

.category-grid--scroll::-webkit-scrollbar {
  display: none;
}

.category-grid button {
  position: relative;
  min-width: 0;
  min-height: 90px;
  padding: 8px 3px 7px;
  display: grid;
  grid-template-columns: 1fr;
  grid-template-rows: 48px 16px;
  align-content: center;
  justify-items: center;
  gap: 5px;
  text-align: center;
  border: 0;
  border-radius: 0;
  color: rgba(255, 255, 255, 0.72);
  background: transparent;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
  transition: transform 180ms cubic-bezier(0.22, 1, 0.36, 1), color 180ms ease, filter 180ms ease;
}

.category-icon-bubble {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.66);
  border: 0;
  border-radius: 0;
  background: transparent;
}

.category-icon-bubble img {
  width: 100%;
  height: 100%;
  padding: 0;
  object-fit: contain;
  border-radius: 8px;
}

.category-name {
  width: min(4em, 100%);
  height: 16px;
  min-width: 0;
  overflow: hidden;
  color: inherit;
  font-size: 11px;
  line-height: 1.3;
  text-align: center;
  white-space: nowrap;
}

.category-name-track {
  display: flex;
  width: max-content;
  min-width: 100%;
  white-space: nowrap;
}

.category-name-copy {
  flex: 0 0 auto;
  padding-right: 22px;
}

.category-name.scrolling .category-name-track {
  animation: category-marquee 7s linear infinite;
  transform: translate3d(0, 0, 0);
  will-change: transform;
}

.category-grid button:hover .category-name.scrolling .category-name-track,
.category-grid button:focus-visible .category-name.scrolling .category-name-track,
.category-grid button:active .category-name.scrolling .category-name-track {
  animation-play-state: paused;
}

.category-state-icon {
  position: absolute;
  top: 5px;
  right: 5px;
  color: rgba(255, 255, 255, 0.36);
}

.category-grid .active {
  color: #effffb;
}

.category-grid .active svg {
  color: #73f8d7;
}

.category-grid .active .category-icon-bubble {
  color: #73f8d7;
  filter: drop-shadow(0 0 10px rgba(0, 255, 195, 0.42));
}

.category-grid .active .category-state-icon {
  color: #73f8d7;
}

.goods-card {
  position: relative;
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px;
  min-height: 112px;
  padding: 9px;
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
  flex-shrink: 0;
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

.goods-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 9px;
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

@keyframes category-marquee {
  0%,
  12% {
    transform: translate3d(0, 0, 0);
  }

  88%,
  100% {
    transform: translate3d(-50%, 0, 0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .category-name.scrolling .category-name-track {
    animation: none;
  }

  .category-name.scrolling {
    overflow-x: auto;
    scrollbar-width: none;
  }

  .category-name.scrolling::-webkit-scrollbar {
    display: none;
  }
}

@media (min-width: 560px) {
  .goods-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
