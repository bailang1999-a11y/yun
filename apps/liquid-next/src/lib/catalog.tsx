"use client";

import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { categoryTree, products, type CategoryNode, type Product } from "@/data/catalog";

interface CategoryContextValue {
  path: CategoryNode[];
  activeLevel: number;
  columns: CategoryNode[][];
  selectedLeaf?: CategoryNode;
  visibleProducts: Product[];
  selectCategory: (level: number, node: CategoryNode) => void;
  goBack: (level: number) => void;
  resetPath: () => void;
}

const CategoryContext = createContext<CategoryContextValue | null>(null);

function collectColumns(path: CategoryNode[]) {
  const columns: CategoryNode[][] = [categoryTree];
  path.forEach((node) => {
    if (node.children?.length) columns.push(node.children);
  });
  return columns.slice(0, 5);
}

function leafIds(node?: CategoryNode): string[] {
  if (!node) return [];
  if (!node.children?.length) return [node.id];
  return node.children.flatMap(leafIds);
}

export function CategoryProvider({ children }: { children: ReactNode }) {
  const [path, setPath] = useState<CategoryNode[]>([]);

  const value = useMemo<CategoryContextValue>(() => {
    const selectedLeaf = path.at(-1);
    const ids = selectedLeaf ? leafIds(selectedLeaf) : products.map((item) => item.categoryId);
    const visibleProducts = products.filter((item) => ids.includes(item.categoryId));

    return {
      path,
      activeLevel: path.length,
      columns: collectColumns(path),
      selectedLeaf,
      visibleProducts,
      selectCategory(level, node) {
        setPath((current) => [...current.slice(0, level), node].slice(0, 5));
      },
      goBack(level) {
        setPath((current) => current.slice(0, level + 1));
      },
      resetPath() {
        setPath([]);
      },
    };
  }, [path]);

  return <CategoryContext.Provider value={value}>{children}</CategoryContext.Provider>;
}

export function useCategory() {
  const value = useContext(CategoryContext);
  if (!value) throw new Error("useCategory must be used within CategoryProvider");
  return value;
}
