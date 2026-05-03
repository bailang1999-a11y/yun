import type { Category } from '../types/operations'

export function buildCategoryTree(items: Category[]) {
  const map = new Map<string, Category>()
  const roots: Category[] = []

  items.forEach((item) => {
    map.set(String(item.id), { ...item, children: [] })
  })

  map.forEach((item) => {
    const parentId = item.parentId ? String(item.parentId) : ''
    const parent = parentId ? map.get(parentId) : undefined
    item.level = parent ? (parent.level || 1) + 1 : 1
    if (parent) parent.children?.push(item)
    else roots.push(item)
  })

  const sortNode = (nodes: Category[]) => {
    nodes.sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0))
    nodes.forEach((node) => sortNode(node.children || []))
  }
  sortNode(roots)
  return roots
}

export function flattenCategoryTree(nodes: Category[], result: Category[] = []) {
  nodes.forEach((node) => {
    result.push(node)
    flattenCategoryTree(node.children || [], result)
  })
  return result
}

export function normalizeLoadedCategories(items: Category[], parentId?: Category['id'], result: Category[] = []) {
  items.forEach((item) => {
    const normalizedParentId = item.parentId ?? parentId
    const children = item.children || []

    result.push({
      ...item,
      parentId: normalizedParentId,
      children: []
    })
    normalizeLoadedCategories(children, item.id, result)
  })

  return result
}
