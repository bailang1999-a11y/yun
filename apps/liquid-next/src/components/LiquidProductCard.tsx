"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { Check, Copy, LoaderCircle, Zap } from "lucide-react";
import type { Product, StockState } from "@/data/catalog";

const stockClass: Record<StockState, string> = {
  full: "shadow-[0_0_40px_rgba(0,255,195,0.22)] before:bg-[#00FFC3]",
  low: "animate-amber-pulse shadow-[0_0_42px_rgba(255,171,0,0.2)] before:bg-[#FFAB00]",
  out: "grayscale opacity-60 shadow-[0_0_24px_rgba(255,59,48,0.12)] before:bg-white/20",
};

export function LiquidProductCard({ product }: { product: Product }) {
  const [paying, setPaying] = useState(false);
  const [success, setSuccess] = useState(false);
  const [copied, setCopied] = useState(false);

  async function simulatePay() {
    if (product.stockState === "out" || paying) return;
    setPaying(true);
    setSuccess(false);
    await new Promise((resolve) => setTimeout(resolve, 900));
    setPaying(false);
    setSuccess(true);
    window.setTimeout(() => setSuccess(false), 1400);
  }

  async function copyCard() {
    setCopied(true);
    await navigator.clipboard?.writeText?.("VIP-7D-LIQUID-2026");
    window.setTimeout(() => setCopied(false), 1200);
  }

  return (
    <motion.article
      layout
      whileTap={{ scale: 0.98, borderRadius: 28 }}
      transition={{ type: "spring", stiffness: 520, damping: 28, mass: 0.72 }}
      className={`liquid-card aurora-edge relative isolate overflow-hidden p-5 before:absolute before:right-5 before:top-5 before:h-2 before:w-2 before:rounded-full before:blur-[1px] ${stockClass[product.stockState]}`}
    >
      <motion.div
        className="pointer-events-none absolute inset-[-30%] rounded-[40%] bg-[conic-gradient(from_90deg,transparent,rgba(72,162,255,0.55),transparent,rgba(0,255,195,0.32),transparent)] opacity-0 blur-2xl"
        animate={{ rotate: paying ? 360 : 0, opacity: paying ? 0.7 : 0 }}
        transition={{ duration: 1.4, repeat: paying ? Infinity : 0, ease: "linear" }}
      />
      {success && (
        <motion.div
          className="pointer-events-none absolute left-1/2 top-1/2 h-12 w-12 rounded-full border border-[#00FFC3]/80 bg-[#00FFC3]/20"
          initial={{ x: "-50%", y: "-50%", scale: 0.2, opacity: 0.9 }}
          animate={{ scale: 9, opacity: 0 }}
          transition={{ duration: 1.05, ease: "easeOut" }}
        />
      )}
      {copied && (
        <motion.div
          className="pointer-events-none absolute inset-0 rounded-[inherit] bg-[#00FFC3]/20"
          initial={{ scale: 0.75, opacity: 0.65 }}
          animate={{ scale: 1.25, opacity: 0 }}
          transition={{ duration: 0.68, ease: "easeOut" }}
        />
      )}

      <div className="relative z-10 flex items-start justify-between gap-4">
        <div>
          <p className="text-xs uppercase tracking-[0.24em] text-white/45">{product.type}</p>
          <h3 className="mt-2 text-lg font-normal text-white transition-[font-weight] group-hover:font-semibold">
            {product.name}
          </h3>
          <p className="mt-1 text-sm text-white/55">{product.faceValue} · {product.delivery}</p>
        </div>
        <span className="rounded-full border border-white/10 bg-white/[0.06] px-3 py-1 text-xs text-white/70 backdrop-blur-xl">
          {product.stockText}
        </span>
      </div>

      <div className="relative z-10 mt-7 flex items-end justify-between gap-4">
        <div>
          <p className="text-xs text-white/45">immersive price</p>
          <p className="metal-price text-5xl font-black leading-none">¥{product.price}</p>
        </div>
        <div className="flex gap-2">
          <motion.button
            whileTap={{ scale: 0.96 }}
            className="grid h-11 w-11 place-items-center rounded-full border border-white/10 bg-white/[0.05] text-white/70 backdrop-blur-3xl"
            onClick={copyCard}
            aria-label="复制卡密"
          >
            {copied ? <Check size={18} /> : <Copy size={18} />}
          </motion.button>
          <motion.button
            whileTap={{ scale: 0.96 }}
            disabled={product.stockState === "out" || paying}
            className="flex h-11 items-center gap-2 rounded-full border border-white/10 bg-white/[0.08] px-4 text-sm font-medium text-white shadow-[0_12px_30px_rgba(0,0,0,0.25)] disabled:cursor-not-allowed disabled:opacity-45"
            onClick={simulatePay}
          >
            {paying ? <LoaderCircle className="animate-spin" size={16} /> : <Zap size={16} />}
            {paying ? "支付中" : success ? "已发卡" : "购买"}
          </motion.button>
        </div>
      </div>
    </motion.article>
  );
}
