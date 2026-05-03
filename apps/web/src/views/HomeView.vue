<template>
  <WebShell>
    <section class="commerce-layout">
      <CategoryTree />

      <div class="content-stack">
        <section class="toolbar">
          <label class="search-box">
            <span>搜索</span>
            <input v-model="catalog.keyword" type="search" placeholder="商品名称 / 面值" @keyup.enter="catalog.reloadGoods()" />
          </label>
          <button class="ghost-button" type="button" @click="catalog.reloadGoods()">刷新</button>
          <span class="toolbar-count">共 {{ catalog.visibleGoods.length }} 个商品</span>
        </section>

        <p v-if="catalog.error" class="alert-line">{{ catalog.error }}</p>
        <div v-if="catalog.loading" class="goods-grid">
          <div v-for="item in 8" :key="item" class="skeleton-card" />
        </div>
        <div v-else-if="catalog.visibleGoods.length" class="goods-grid">
          <GoodsCard v-for="goods in catalog.visibleGoods" :key="goods.id" :goods="goods" />
        </div>
        <EmptyState v-else title="暂无匹配商品" description="换个分类或关键词试试。" />
      </div>
    </section>
  </WebShell>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import WebShell from '../components/WebShell.vue'
import CategoryTree from '../components/CategoryTree.vue'
import GoodsCard from '../components/GoodsCard.vue'
import EmptyState from '../components/EmptyState.vue'
import { useCatalogStore } from '../stores/catalog'

const catalog = useCatalogStore()

onMounted(() => {
  if (!catalog.goods.length) void catalog.loadCatalog()
})
</script>
