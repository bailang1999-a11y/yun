"use client";

import { AnimatePresence, motion, useMotionValue, useTransform } from "framer-motion";
import { ChevronDown } from "lucide-react";
import { useCategory } from "@/lib/catalog";
import { FluidBreadcrumbs } from "./FluidBreadcrumbs";
import { LiquidProductCard } from "./LiquidProductCard";
import { LiquidProgressBar } from "./LiquidProgressBar";

export function LiquidDrawerNavigator() {
  const { columns, path, activeLevel, visibleProducts, selectCategory, goBack } = useCategory();
  const current = columns[Math.min(activeLevel, columns.length - 1)] ?? columns[0];
  const y = useMotionValue(0);
  const opacity = useTransform(y, [0, 180], [1, 0.35]);

  return (
    <section className="min-h-screen overflow-hidden px-4 pb-4 pt-5 md:hidden">
      <header className="liquid-panel sticky top-3 z-30 p-4">
        <p className="text-xs uppercase tracking-[0.24em] text-white/35">Liquid Drawer</p>
        <h1 className="mt-1 text-2xl font-normal text-white">单手类目选择</h1>
        <div className="mt-4"><LiquidProgressBar level={activeLevel} /></div>
        <FluidBreadcrumbs />
      </header>

      <div className="mt-4 space-y-3">
        {visibleProducts.slice(0, 2).map((product) => (
          <LiquidProductCard product={product} key={product.id} />
        ))}
      </div>

      <AnimatePresence mode="popLayout">
        <motion.div
          key={activeLevel}
          drag="y"
          dragConstraints={{ top: 0, bottom: 220 }}
          style={{ y, opacity, backdropFilter: `blur(${24 + activeLevel * 6}px) saturate(180%)` }}
          onDragEnd={(_, info) => {
            if (info.offset.y > 90 && activeLevel > 0) goBack(activeLevel - 2 < 0 ? 0 : activeLevel - 2);
          }}
          initial={{ y: 380, opacity: 0, borderRadius: 42 }}
          animate={{ y: 0, opacity: 1, borderRadius: 30 }}
          exit={{ y: 420, opacity: 0 }}
          transition={{ type: "spring", stiffness: 180, damping: 22, mass: 0.78 }}
          className="fixed inset-x-3 bottom-3 z-40 max-h-[62vh] overflow-hidden rounded-[30px] border border-white/10 bg-white/[0.045] shadow-[0_-28px_80px_rgba(0,0,0,0.45)]"
        >
          <div className="mx-auto mt-3 h-1.5 w-12 rounded-full bg-white/25" />
          <div className="flex items-center justify-between px-5 py-4">
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-white/35">Level {activeLevel + 1}</p>
              <h2 className="text-xl font-normal text-white">{path.at(-1)?.label ?? "全部类目"}</h2>
            </div>
            <ChevronDown className="text-white/45" />
          </div>
          <div className="grid max-h-[45vh] gap-2 overflow-y-auto px-4 pb-5">
            {current.map((node) => {
              const Icon = node.icon;
              return (
                <motion.button
                  layoutId={`drawer-${node.id}`}
                  whileTap={{ scale: 0.98, boxShadow: "0 20px 64px rgba(0,0,0,0.4)" }}
                  transition={{ type: "spring", stiffness: 520, damping: 28, mass: 0.72 }}
                  key={node.id}
                  onClick={() => selectCategory(activeLevel, node)}
                  className="flex items-center gap-3 rounded-3xl border border-white/10 bg-white/[0.05] p-4 text-left text-white backdrop-blur-[40px]"
                >
                  <span className="grid h-11 w-11 place-items-center rounded-full bg-white/[0.08] text-white/75">
                    <Icon size={18} />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-base font-normal">{node.label}</span>
                    <span className="block truncate text-sm text-white/45">{node.hint}</span>
                  </span>
                </motion.button>
              );
            })}
          </div>
        </motion.div>
      </AnimatePresence>
    </section>
  );
}
