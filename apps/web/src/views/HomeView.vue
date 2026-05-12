<template>
  <WebShell>
    <section class="commerce-layout">
      <CategoryTree />

      <div class="content-stack">
        <section class="toolbar">
          <div class="toolbar-main">
            <label class="search-box">
              <span>搜索</span>
              <input v-model="catalog.keyword" type="search" placeholder="商品名称 / 面值" @keyup.enter="catalog.reloadGoods()" />
            </label>
            <button class="ghost-button" type="button" @click="catalog.reloadGoods()">刷新</button>
            <span class="toolbar-count">共 {{ catalog.visibleGoods.length }} 个商品</span>
          </div>
          <div v-if="catalog.visibleSourceChannels.length" class="source-channel-tags" aria-label="货源渠道">
            <span class="source-channel-label">货源渠道</span>
            <span v-for="channel in catalog.visibleSourceChannels" :key="channel" class="source-channel-tag">{{ channel }}</span>
          </div>
        </section>

        <p v-if="catalog.error" class="alert-line">{{ catalog.error }}</p>
        <div v-if="catalog.loading" class="goods-list">
          <div v-for="item in 6" :key="item" class="skeleton-card goods-list-skeleton" />
        </div>
        <div v-else-if="catalog.visibleGoods.length" class="goods-list">
          <GoodsCard v-for="goods in catalog.visibleGoods" :key="goods.id" :goods="goods" />
        </div>
        <EmptyState v-else title="暂无匹配商品" description="换个分类或关键词试试。" />
      </div>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import WebShell from '../components/WebShell.vue'
import CategoryTree from '../components/CategoryTree.vue'
import GoodsCard from '../components/GoodsCard.vue'
import EmptyState from '../components/EmptyState.vue'
import { useCatalogStore } from '../stores/catalog'

const catalog = useCatalogStore()

function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void catalog.loadCatalog()
}

function refreshCatalog() {
  void catalog.loadCatalog()
}

onMounted(() => {
  void catalog.loadCatalog()
  window.addEventListener('focus', refreshCatalog)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshCatalog)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>
