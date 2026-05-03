import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchCategories, fetchGoods } from '../api/web'
import type { CategoryItem, GoodsItem } from '../types/web'

const fallbackCategories: CategoryItem[] = [
  { id: 'video', name: '会员权益', level: 1 },
  { id: 'video-streaming', name: '视频平台', parentId: 'video', level: 2 },
  { id: 'game', name: '游戏点卡', level: 1 },
  { id: 'game-mobile', name: '手游充值', parentId: 'game', level: 2 },
  { id: 'phone', name: '话费直充', level: 1 },
  { id: 'agent', name: '代充专区', level: 1 }
]

const fallbackGoods: GoodsItem[] = [
  {
    id: '1001',
    name: '视频会员月卡自动发货',
    faceValue: '30天',
    price: 18.8,
    originalPrice: 29.9,
    type: 'CARD',
    stockLabel: '库存 18',
    category: '视频平台',
    categoryId: 'video-streaming',
    cover: 'CARD',
    requireRechargeAccount: false,
    accountTypes: []
  },
  {
    id: '1002',
    name: '手游点券直充秒到账',
    faceValue: '6480点',
    price: 598,
    type: 'DIRECT',
    stockLabel: '自动充值',
    category: '手游充值',
    categoryId: 'game-mobile',
    cover: 'API',
    requireRechargeAccount: true,
    accountTypes: ['game_uid']
  },
  {
    id: '1003',
    name: '海外账号人工代充',
    faceValue: '100 USD',
    price: 735,
    type: 'MANUAL',
    stockLabel: '人工处理',
    category: '代充专区',
    categoryId: 'agent',
    cover: 'MAN',
    requireRechargeAccount: true,
    accountTypes: ['email']
  },
  {
    id: '1004',
    name: '全国话费快充',
    faceValue: '100元',
    price: 99.2,
    type: 'DIRECT',
    stockLabel: '库存充足',
    category: '话费直充',
    categoryId: 'phone',
    cover: 'TEL',
    requireRechargeAccount: true,
    accountTypes: ['mobile']
  }
]

export const useCatalogStore = defineStore('catalog', () => {
  const categories = ref<CategoryItem[]>(fallbackCategories)
  const goods = ref<GoodsItem[]>(fallbackGoods)
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

  async function loadCatalog() {
    loading.value = true
    error.value = ''
    try {
      const remoteCategories = await fetchCategories()
      categories.value = remoteCategories.length ? remoteCategories : fallbackCategories
      const remoteGoods = await fetchGoods(categories.value, { platform: 'pc' })
      goods.value = remoteGoods.length ? remoteGoods : fallbackGoods
    } catch (err) {
      categories.value = fallbackCategories
      goods.value = fallbackGoods
      error.value = err instanceof Error ? err.message : '商品加载失败'
    } finally {
      loading.value = false
    }
  }

  async function reloadGoods() {
    loading.value = true
    error.value = ''
    try {
      const remoteGoods = await fetchGoods(categories.value, { platform: 'pc', search: keyword.value })
      goods.value = remoteGoods.length ? remoteGoods : fallbackGoods
    } catch (err) {
      goods.value = fallbackGoods
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
    childrenOf,
    categoryName,
    loadCatalog,
    reloadGoods
  }
})
