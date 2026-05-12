import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCategories, fetchGoods } from '../api/web'
import type { CategoryItem, GoodsItem } from '../types/web'

export const useCatalogStore = defineStore('catalog', () => {
  const categories = ref<CategoryItem[]>([])
  const goods = ref<GoodsItem[]>([])
  const activeCategoryId = ref('')
  const keyword = ref('')
  const loading = ref(false)
  const error = ref('')

  const rootCategories = computed(() => categories.value.filter((item) => !item.parentId))

  function childrenOf(parentId: string) {
    return categories.value.filter((item) => item.parentId === parentId)
  }

  function categoryName(id?: string) {
    return categories.value.find((item) => item.id === id)?.name || ''
  }

  function descendantIds(id: string): string[] {
    const direct = childrenOf(id)
    return [id, ...direct.flatMap((item) => descendantIds(item.id))]
  }

  const visibleGoods = computed(() => {
    const term = keyword.value.trim().toLowerCase()
    const scopedIds = activeCategoryId.value ? new Set(descendantIds(activeCategoryId.value)) : null
    return goods.value.filter((item) => {
      const matchCategory = !scopedIds || scopedIds.has(item.categoryId || '')
      const matchKeyword = !term || item.name.toLowerCase().includes(term) || item.faceValue.toLowerCase().includes(term)
      return matchCategory && matchKeyword
    })
  })

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

  async function loadCatalog() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      const remoteCategories = await fetchCategories()
      categories.value = remoteCategories
      goods.value = await fetchGoods(categories.value)
    } catch (err) {
      categories.value = []
      goods.value = []
      error.value = err instanceof Error ? err.message : '商品加载失败'
    } finally {
      loading.value = false
    }
  }

  async function reloadGoods() {
    if (loading.value) return
    loading.value = true
    error.value = ''
    try {
      goods.value = await fetchGoods(categories.value, { search: keyword.value })
    } catch (err) {
      goods.value = []
      error.value = err instanceof Error ? err.message : '商品加载失败'
    } finally {
      loading.value = false
    }
  }

  return {
    categories,
    goods,
    activeCategoryId,
    keyword,
    loading,
    error,
    rootCategories,
    visibleGoods,
    visibleSourceChannels,
    childrenOf,
    categoryName,
    loadCatalog,
    reloadGoods
  }
})
