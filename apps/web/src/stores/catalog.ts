import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCategories, fetchGoodsPage } from '../api/web'
import type { CategoryItem, GoodsItem } from '../types/web'

export const useCatalogStore = defineStore('catalog', () => {
  const categories = ref<CategoryItem[]>([])
  const goods = ref<GoodsItem[]>([])
  const activeCategoryId = ref('')
  const keyword = ref('')
  const loading = ref(false)
  const error = ref('')
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)

  const rootCategories = computed(() => categories.value.filter((item) => !item.parentId))

  function childrenOf(parentId: string) {
    return categories.value.filter((item) => item.parentId === parentId)
  }

  function categoryName(id?: string) {
    return categories.value.find((item) => item.id === id)?.name || ''
  }

  const visibleGoods = computed(() => goods.value)
  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  const pageStart = computed(() => (total.value ? (page.value - 1) * pageSize.value + 1 : 0))
  const pageEnd = computed(() => Math.min(page.value * pageSize.value, total.value))

  const visibleSourceChannels = computed(() => {
    const seen = new Set<string>()
    return visibleGoods.value
      .flatMap((item) => item.sourceChannels || [])
      .map((item) => item.trim())
      .filter((item) => {
        if (!item || seen.has(item)) return false
        seen.add(item)
        return true
      })
  })

  async function requestGoodsPage(nextPage: number) {
    const result = await fetchGoodsPage(categories.value, {
      categoryId: activeCategoryId.value || undefined,
      search: keyword.value,
      page: nextPage,
      pageSize: pageSize.value
    })
    goods.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
  }

  async function loadCatalog() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      const remoteCategories = await fetchCategories()
      categories.value = remoteCategories
      if (activeCategoryId.value && !categories.value.some((item) => item.id === activeCategoryId.value)) {
        activeCategoryId.value = ''
      }
      await requestGoodsPage(1)
    } catch (err) {
      categories.value = []
      goods.value = []
      total.value = 0
      error.value = err instanceof Error ? err.message : '商品加载失败'
    } finally {
      loading.value = false
    }
  }

  async function reloadGoods() {
    await loadGoods(1)
  }

  async function loadGoods(nextPage = page.value) {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      await requestGoodsPage(nextPage)
    } catch (err) {
      goods.value = []
      total.value = 0
      error.value = err instanceof Error ? err.message : '商品加载失败'
    } finally {
      loading.value = false
    }
  }

  async function selectCategory(categoryId: string) {
    if (loading.value || activeCategoryId.value === categoryId) return
    activeCategoryId.value = categoryId
    await loadGoods(1)
  }

  async function goToPage(nextPage: number) {
    const target = Math.min(Math.max(1, nextPage), pageCount.value)
    if (loading.value || target === page.value) return
    await loadGoods(target)
  }

  return {
    categories,
    goods,
    activeCategoryId,
    keyword,
    loading,
    error,
    page,
    pageSize,
    total,
    pageCount,
    pageStart,
    pageEnd,
    rootCategories,
    visibleGoods,
    visibleSourceChannels,
    childrenOf,
    categoryName,
    loadCatalog,
    reloadGoods,
    selectCategory,
    goToPage
  }
})
