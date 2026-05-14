import { defineStore } from 'pinia'
import { getApiErrorMessage } from '../api/client'
import { fetchH5Categories, fetchH5GoodsPage } from '../api/h5'
import type { GoodsCard, H5Category } from '../types/h5'

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
    searchKeyword: '',
    activePath: [] as string[],
    categories: [] as H5Category[],
    goods: [] as GoodsCard[],
    loading: false,
    loadingMore: false,
    page: 1,
    pageSize: 10,
    total: 0,
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
      if (!state.activePath.length) return 0
      return Math.min(state.activePath.length / (state.activePath.length + 1), 0.92)
    },
    visibleGoods(state) {
      return state.goods
    }
  },
  actions: {
    selectCategory(category: H5Category) {
      const level = Math.max(levelOf(this.categories, category) - 1, 0)
      this.activePath = [...this.activePath.slice(0, level), category.id]
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
      if (this.loading) return
      this.loading = true
      this.errorMessage = ''

      try {
        const categories = await fetchH5Categories()
        this.categories = categories.filter((item) => item.id !== 'all')
        this.activePath = this.activePath.filter((id) => this.categories.some((item) => item.id === id))
        await this.loadGoods()
      } catch (error) {
        this.categories = []
        this.goods = []
        this.total = 0
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
          platform: 'h5',
          page: this.page,
          pageSize: this.pageSize
        })
        this.goods = result.items
        this.total = result.total
      } catch (error) {
        this.goods = []
        this.total = 0
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
          platform: 'h5',
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
