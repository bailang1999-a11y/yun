"use client";

import { motion } from "framer-motion";
import { ChevronLeft, X } from "lucide-react";
import { useCategory } from "@/lib/catalog";

export function FluidBreadcrumbs() {
  const { path, goBack, resetPath } = useCategory();

  return (
    <div className="flex min-w-0 items-center gap-2 overflow-x-auto py-2">
      <motion.button
        whileTap={{ scale: 0.95 }}
        className="glass-bubble flex items-center gap-1 px-3 py-2 text-xs text-white/70"
        onClick={resetPath}
      >
        <ChevronLeft size={14} />
        全部
      </motion.button>
      {path.map((node, index) => {
        const Icon = node.icon;
        return (
          <motion.button
            layoutId={`crumb-${node.id}`}
            key={node.id}
            whileTap={{ scale: 0.95 }}
            className="glass-bubble flex shrink-0 items-center gap-2 px-3 py-2 text-xs text-white/80"
            onClick={() => goBack(index)}
          >
            <Icon size={14} />
            <span>{node.label}</span>
          </motion.button>
        );
      })}
      {!!path.length && (
        <motion.button whileTap={{ scale: 0.95 }} onClick={resetPath} className="glass-bubble p-2 text-white/55">
          <X size={14} />
        </motion.button>
      )}
    </div>
  );
}
