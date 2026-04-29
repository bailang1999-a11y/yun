import { defineStore } from 'pinia'
import { getApiErrorMessage } from '../api/client'
import { fetchH5Categories, fetchH5GoodsPage } from '../api/h5'
import type { GoodsCard, H5Category } from '../types/h5'

const fallbackCategories: H5Category[] = [
  { id: 'video', name: '影音会员', level: 1 },
  { id: 'video-streaming', name: '视频平台', parentId: 'video', level: 2 },
  { id: 'video-monthly', name: '月卡专区', parentId: 'video-streaming', level: 3 },
  { id: 'video-auto', name: '自动发卡', parentId: 'video-monthly', level: 4 },
  { id: 'video-vip', name: '会员月卡', parentId: 'video-auto', level: 5 },
  { id: 'game', name: '游戏点卡', level: 1 },
  { id: 'game-mobile', name: '手游充值', parentId: 'game', level: 2 },
  { id: 'game-points', name: '点券直充', parentId: 'game-mobile', level: 3 },
  { id: 'game-api', name: 'API 秒充', parentId: 'game-points', level: 4 },
  { id: 'game-hot', name: '热门大区', parentId: 'game-api', level: 5 },
  { id: 'phone', name: '话费直充', level: 1 },
  { id: 'agent', name: '代充专区', level: 1 },
  { id: 'agent-overseas', name: '海外账号', parentId: 'agent', level: 2 },
  { id: 'agent-manual', name: '人工处理', parentId: 'agent-overseas', level: 3 }
]

const fallbackGoods: GoodsCard[] = [
  {
    id: '1001',
    name: '视频会员月卡自动发货',
    faceValue: '30天',
    price: 18.8,
    originalPrice: 29.9,
    type: 'CARD',
    stockLabel: '仅剩 18 张',
    category: '影音会员',
    categoryId: 'video-vip',
    cover: 'CARD',
    requireRechargeAccount: false
  },
  {
    id: '1002',
    name: '手游点券直充秒到账',
    faceValue: '6480点',
    price: 598,
    type: 'DIRECT',
    stockLabel: '自动充值',
    category: '游戏点卡',
    categoryId: 'game-hot',
    cover: 'API',
    requireRechargeAccount: true
  },
  {
    id: '1003',
    name: '海外账号人工代充',
    faceValue: '100 USD',
    price: 735,
    type: 'MANUAL',
    stockLabel: '人工充值',
    category: '代充专区',
    categoryId: 'agent-manual',
    cover: 'MAN',
    requireRechargeAccount: true
  }
]

function parentKey(category: H5Category) {
  return category.parentId || ''
}

function childrenOf(categories: H5Category[], parentId = '') {
  return categories.filter((item) => parentKey(item) === parentId)
}

function levelOf(categories: H5Category[], category: H5Category): number {
  if (category.level) return category.level
  if (!category.parentId) return 1
  const parent = categories.find((item) => item.id === category.parentId)
  return parent ? levelOf(categories, parent) + 1 : 1
}

export const useCatalogStore = defineStore('catalog', {
  state: () => ({
    platformCode: 'h5',
    searchKeyword: '',
    activePath: [] as string[],
    categories: fallbackCategories,
    goods: fallbackGoods,
    loading: false,
    loadingMore: false,
    page: 1,
    pageSize: 10,
    total: fallbackGoods.length,
    errorMessage: ''
  }),
  getters: {
    activeCategory(state) {
      const currentId = state.activePath.at(-1)
      return currentId ? state.categories.find((item) => item.id === currentId) : undefined
    },
    activeTrail(state) {
      return state.activePath
        .map((id) => state.categories.find((item) => item.id === id))
        .filter((item): item is H5Category => Boolean(item))
    },
    currentCategories(state) {
      const parentId = state.activePath.at(-1) || ''
      const next = childrenOf(state.categories, parentId)
      if (next.length) return next

      const parentOfLeaf = state.activePath.at(-2) || ''
      return childrenOf(state.categories, parentOfLeaf)
    },
    depthProgress(state) {
      return Math.min(state.activePath.length / 5, 1)
    },
    visibleGoods(state) {
      return state.goods
    }
  },
  actions: {
    selectCategory(category: H5Category) {
      const level = Math.max(levelOf(this.categories, category) - 1, 0)
      this.activePath = [...this.activePath.slice(0, level), category.id].slice(0, 5)
      void this.loadGoods()
    },
    goToDepth(depth: number) {
      this.activePath = this.activePath.slice(0, depth + 1)
      void this.loadGoods()
    },
    resetCategory() {
      this.activePath = []
      void this.loadGoods()
    },
    setSearchKeyword(keyword: string) {
      this.searchKeyword = keyword
      void this.loadGoods()
    },
    async loadCatalog() {
      this.loading = true
      this.errorMessage = ''

      try {
        const categories = await fetchH5Categories()
        this.categories = categories.length ? categories.filter((item) => item.id !== 'all') : fallbackCategories
        this.activePath = this.activePath.filter((id) => this.categories.some((item) => item.id === id))
        await this.loadGoods()
      } catch (error) {
        this.categories = fallbackCategories
        this.goods = fallbackGoods
        this.errorMessage = getApiErrorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async loadGoods() {
      this.loading = true
      this.errorMessage = ''
      this.page = 1

      try {
        const categoryId = this.activePath.at(-1)
        const result = await fetchH5GoodsPage(this.categories, {
          categoryId,
          search: this.searchKeyword,
          platform: this.platformCode,
          page: this.page,
          pageSize: this.pageSize
        })
        this.goods = result.items
        this.total = result.total
      } catch (error) {
        this.goods = fallbackGoods
        this.total = fallbackGoods.length
        this.errorMessage = getApiErrorMessage(error)
      } finally {
        this.loading = false
      }
    },
    async loadMoreGoods() {
      if (this.loading || this.loadingMore || this.goods.length >= this.total) return
      this.loadingMore = true
      this.errorMessage = ''

      try {
        const nextPage = this.page + 1
        const categoryId = this.activePath.at(-1)
        const result = await fetchH5GoodsPage(this.categories, {
          categoryId,
          search: this.searchKeyword,
          platform: this.platformCode,
          page: nextPage,
          pageSize: this.pageSize
        })
        this.goods = [...this.goods, ...result.items]
        this.total = result.total
        this.page = result.page
      } catch (error) {
        this.errorMessage = getApiErrorMessage(error)
      } finally {
        this.loadingMore = false
      }
    }
  }
})
