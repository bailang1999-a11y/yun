"use client";

import { motion } from "framer-motion";

export function LiquidProgressBar({ level }: { level: number }) {
  const progress = Math.min(level / 5, 1) * 100;

  return (
    <div className="relative h-3 overflow-hidden rounded-full border border-white/10 bg-white/[0.04] shadow-[inset_0_1px_12px_rgba(255,255,255,0.12)] backdrop-blur-2xl">
      <motion.div
        className="absolute inset-y-0 left-0 rounded-full bg-[linear-gradient(90deg,rgba(0,255,195,0.25),rgba(88,166,255,0.65),rgba(255,255,255,0.82))]"
        animate={{ width: `${progress}%` }}
        transition={{ type: "spring", stiffness: 170, damping: 24, mass: 0.8 }}
      />
      <motion.div
        className="absolute inset-y-[-10px] w-24 rounded-full bg-white/35 blur-xl"
        animate={{ x: `${progress * 4.2}%`, opacity: level ? 0.72 : 0 }}
        transition={{ type: "spring", stiffness: 130, damping: 20 }}
      />
    </div>
  );
}
