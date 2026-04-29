"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { ChevronRight } from "lucide-react";
import { useCategory } from "@/lib/catalog";
import { LiquidProductCard } from "./LiquidProductCard";
import { LiquidProgressBar } from "./LiquidProgressBar";
import { FluidBreadcrumbs } from "./FluidBreadcrumbs";

const blurByLevel = [10, 20, 30, 40, 50];

export function SpatialMillerColumns() {
  const { columns, path, activeLevel, visibleProducts, selectCategory } = useCategory();
  const [focusColumn, setFocusColumn] = useState<number | null>(null);

  return (
    <section className="hidden min-h-screen p-6 md:block">
      <div className="mx-auto flex max-w-7xl flex-col gap-5">
        <header className="liquid-panel p-5">
          <div className="flex items-end justify-between gap-6">
            <div>
              <p className="text-sm uppercase tracking-[0.28em] text-white/40">Spatial Multi-Column</p>
              <h1 className="mt-2 text-4xl font-normal text-white">五级类目 Liquid Miller Columns</h1>
            </div>
            <div className="w-80">
              <LiquidProgressBar level={activeLevel} />
              <p className="mt-2 text-right text-xs text-white/45">Level {activeLevel}/5</p>
            </div>
          </div>
          <FluidBreadcrumbs />
        </header>

        <div className="grid grid-cols-[minmax(0,1.25fr)_420px] gap-5">
          <div className="flex min-w-0 gap-3 overflow-x-auto pb-2">
            {columns.map((column, level) => (
              <motion.div
                layout
                key={level}
                onMouseEnter={() => setFocusColumn(level)}
                onMouseLeave={() => setFocusColumn(null)}
                animate={{
                  width: focusColumn === level ? 290 : 236,
                  opacity: focusColumn === null || focusColumn === level ? 1 : 0.46,
                  filter: focusColumn === null || focusColumn === level ? "blur(0px)" : "blur(2.5px)",
                  y: focusColumn === level ? -8 : 0,
                }}
                transition={{ type: "spring", stiffness: 230, damping: 28 }}
                className="liquid-panel aurora-edge shrink-0 p-3"
                style={{ backdropFilter: `blur(${blurByLevel[level] ?? 50}px) saturate(180%)` }}
              >
                <div className="mb-3 flex items-center justify-between px-2">
                  <p className="text-xs uppercase tracking-[0.22em] text-white/35">Level {level + 1}</p>
                  <span className="h-2 w-2 rounded-full bg-[#00FFC3] shadow-[0_0_18px_#00FFC3]" />
                </div>
                <div className="space-y-2">
                  {column.map((node) => {
                    const selected = path[level]?.id === node.id;
                    const Icon = node.icon;
                    return (
                      <motion.button
                        layoutId={`category-${node.id}`}
                        key={node.id}
                        whileTap={{ scale: 0.98, boxShadow: "0 18px 52px rgba(0,0,0,0.34)" }}
                        transition={{ type: "spring", stiffness: 520, damping: 28, mass: 0.72 }}
                        onClick={() => selectCategory(level, node)}
                        className={`group relative flex w-full items-center gap-3 overflow-hidden rounded-2xl border px-3 py-3 text-left transition ${
                          selected ? "border-white/25 bg-white/[0.10]" : "border-white/10 bg-white/[0.03] hover:bg-white/[0.07]"
                        }`}
                      >
                        {selected && <motion.div layoutId="active-category-glow" className="absolute inset-0 bg-[#00FFC3]/10 blur-xl" />}
                        <span className="relative grid h-9 w-9 place-items-center rounded-full bg-white/[0.08] text-white/75">
                          <Icon size={17} />
                        </span>
                        <span className="relative min-w-0 flex-1">
                          <span className="block text-sm font-normal text-white transition-[font-weight] group-hover:font-semibold">
                            {node.label}
                          </span>
                          <span className="block truncate text-xs text-white/42">{node.hint}</span>
                        </span>
                        {!!node.children?.length && <ChevronRight className="relative text-white/35" size={16} />}
                      </motion.button>
                    );
                  })}
                </div>
              </motion.div>
            ))}
          </div>

          <aside className="space-y-3">
            {visibleProducts.map((product) => (
              <LiquidProductCard product={product} key={product.id} />
            ))}
          </aside>
        </div>
      </div>
    </section>
  );
}
