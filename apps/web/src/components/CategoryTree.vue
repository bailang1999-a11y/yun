<template>
  <section class="category-board">
    <article class="category-canvas">
      <div class="category-head">
        <div>
          <h2>商品分类</h2>
          <span class="category-breadcrumb">
            目录 / 分类
            <em>{{ catalog.categories.length }} 个节点</em>
            <em>当前 {{ selectedCategoryName }}</em>
          </span>
        </div>
        <button class="ghost-button compact-button" type="button" @click="catalog.loadCatalog()">刷新</button>
      </div>

      <nav class="root-tabs" aria-label="一级分类">
        <button type="button" :class="{ active: !catalog.activeCategoryId }" @click="selectAll">全部商品</button>
        <button
          v-for="item in catalog.rootCategories"
          :key="item.id"
          type="button"
          :class="{ active: selectedRoot?.id === item.id && catalog.activeCategoryId }"
          @click="selectRoot(item)"
        >
          {{ item.name }}
        </button>
      </nav>

      <section class="category-level" aria-label="二级分类">
        <div class="level-title">
          <strong>二级分类</strong>
          <span>{{ selectedRoot ? `归属 ${selectedRoot.name}` : '请选择一级分类' }}</span>
        </div>
        <div class="category-strip">
          <button
            v-for="item in secondLevelCategories"
            :key="item.id"
            type="button"
            class="category-icon-card"
            :class="{ active: catalog.activeCategoryId === item.id || selectedSecond?.id === item.id }"
            @click="selectSecond(item)"
          >
            <span class="icon-bubble">
              <component :is="iconForCategory(item.name)" :size="28" />
            </span>
            <strong>{{ item.name }}</strong>
            <em>{{ catalog.childrenOf(item.id).length }} 个子类</em>
          </button>
          <div v-if="!secondLevelCategories.length" class="category-empty">暂无二级分类</div>
        </div>
      </section>

      <section v-if="thirdLevelCategories.length" class="category-level" aria-label="三级分类">
        <div class="level-title">
          <strong>三级分类</strong>
          <span>{{ selectedSecond ? `归属 ${selectedSecond.name}` : '请选择二级分类' }}</span>
        </div>
        <div class="category-matrix">
          <button
            v-for="item in thirdLevelCategories"
            :key="item.id"
            type="button"
            class="category-icon-card"
            :class="{ active: catalog.activeCategoryId === item.id }"
            @click="catalog.activeCategoryId = item.id"
          >
            <span class="icon-bubble">
              <component :is="iconForCategory(item.name)" :size="26" />
            </span>
            <strong>{{ item.name }}</strong>
            <em>第 {{ item.level || 3 }} 级</em>
            <small data-enabled="true">启用</small>
          </button>
        </div>
      </section>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BadgeIcon, BriefcaseBusiness, Film, Flame, Gamepad2, HeartHandshake, MonitorCog, Rocket, Smartphone } from 'lucide-vue-next'
import { useCatalogStore } from '../stores/catalog'
import type { CategoryItem } from '../types/web'

const catalog = useCatalogStore()

const selectedRoot = computed(() => {
  if (!catalog.activeCategoryId) return catalog.rootCategories[0]
  return catalog.rootCategories.find((root) => root.id === catalog.activeCategoryId || hasDescendant(root.id, catalog.activeCategoryId))
})

const secondLevelCategories = computed(() => (selectedRoot.value ? catalog.childrenOf(selectedRoot.value.id) : []))
const selectedSecond = computed(() => {
  if (!catalog.activeCategoryId) return secondLevelCategories.value[0]
  return secondLevelCategories.value.find((item) => item.id === catalog.activeCategoryId || hasDescendant(item.id, catalog.activeCategoryId))
})
const thirdLevelCategories = computed(() => (selectedSecond.value ? catalog.childrenOf(selectedSecond.value.id) : []))
const selectedCategoryName = computed(() => {
  if (!catalog.activeCategoryId) return '全部商品'
  return catalog.categoryName(catalog.activeCategoryId) || selectedRoot.value?.name || '未选择'
})

function selectAll() {
  catalog.activeCategoryId = ''
}

function selectRoot(item: CategoryItem) {
  catalog.activeCategoryId = item.id
}

function selectSecond(item: CategoryItem) {
  catalog.activeCategoryId = item.id
}

function hasDescendant(parentId: string, targetId: string): boolean {
  return catalog.childrenOf(parentId).some((child) => child.id === targetId || hasDescendant(child.id, targetId))
}

function iconForCategory(name: string) {
  if (/视频|会员|影视|音频/.test(name)) return Film
  if (/游戏|点券|手游/.test(name)) return Gamepad2
  if (/话费|手机|流量/.test(name)) return Smartphone
  if (/人工|代办|资料|办公/.test(name)) return BriefcaseBusiness
  if (/API|秒充|加速|直充/.test(name)) return Rocket
  if (/热门|特惠|福利/.test(name)) return Flame
  if (/生活|服务|权益/.test(name)) return HeartHandshake
  if (/测试|监控/.test(name)) return MonitorCog
  return BadgeIcon
}
</script>
