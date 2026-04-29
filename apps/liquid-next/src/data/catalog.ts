import { BadgeCheck, Gamepad2, Gift, KeyRound, Smartphone, Sparkles, Tv, WalletCards } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export type StockState = "full" | "low" | "out";
export type ProductType = "card" | "direct" | "manual";

export interface CategoryNode {
  id: string;
  label: string;
  hint: string;
  icon: LucideIcon;
  children?: CategoryNode[];
}

export interface Product {
  id: string;
  categoryId: string;
  name: string;
  type: ProductType;
  price: string;
  faceValue: string;
  stockState: StockState;
  stockText: string;
  accent: string;
  delivery: string;
}

export const categoryTree: CategoryNode[] = [
  {
    id: "membership",
    label: "会员权益",
    hint: "卡密自动发货",
    icon: BadgeCheck,
    children: [
      {
        id: "video",
        label: "影音会员",
        hint: "周卡/月卡/季卡",
        icon: Tv,
        children: [
          {
            id: "iqiyi",
            label: "爱奇艺",
            hint: "激活码库存",
            icon: Sparkles,
            children: [
              {
                id: "iqiyi-week",
                label: "周卡",
                hint: "轻量套餐",
                icon: KeyRound,
                children: [
                  { id: "iqiyi-week-auto", label: "自动发卡", hint: "秒级交付", icon: Gift },
                  { id: "iqiyi-week-gift", label: "礼品兑换", hint: "附教程", icon: Gift },
                ],
              },
              {
                id: "iqiyi-month",
                label: "月卡",
                hint: "主推库存",
                icon: KeyRound,
                children: [
                  { id: "iqiyi-month-auto", label: "自动发卡", hint: "库存 128", icon: Gift },
                  { id: "iqiyi-month-low", label: "低库存批次", hint: "库存告急", icon: Gift },
                ],
              },
            ],
          },
          {
            id: "tencent-video",
            label: "腾讯视频",
            hint: "兑换链接",
            icon: Sparkles,
            children: [
              {
                id: "tencent-month",
                label: "月卡",
                hint: "热门",
                icon: KeyRound,
                children: [{ id: "tencent-month-auto", label: "自动发卡", hint: "秒发", icon: Gift }],
              },
            ],
          },
        ],
      },
    ],
  },
  {
    id: "game",
    label: "游戏点卡",
    hint: "上游直充",
    icon: Gamepad2,
    children: [
      {
        id: "mobile-game",
        label: "手游直充",
        hint: "API 采购",
        icon: Smartphone,
        children: [
          {
            id: "valor",
            label: "荣耀点券",
            hint: "账号充值",
            icon: WalletCards,
            children: [
              {
                id: "valor-60",
                label: "60 点券",
                hint: "自动采购",
                icon: Sparkles,
                children: [{ id: "valor-60-api", label: "优先渠道", hint: "蓝色路由", icon: Gift }],
              },
            ],
          },
        ],
      },
    ],
  },
  {
    id: "agency",
    label: "代充专区",
    hint: "人工处理",
    icon: Gift,
    children: [
      {
        id: "global",
        label: "海外服务",
        hint: "人工核验",
        icon: Sparkles,
        children: [
          {
            id: "global-wallet",
            label: "钱包充值",
            hint: "工单履约",
            icon: WalletCards,
            children: [
              {
                id: "global-wallet-100",
                label: "100 USD",
                hint: "预计 2 小时",
                icon: KeyRound,
                children: [{ id: "global-wallet-100-manual", label: "人工代充", hint: "客服确认", icon: Gift }],
              },
            ],
          },
        ],
      },
    ],
  },
];

export const products: Product[] = [
  {
    id: "p-001",
    categoryId: "iqiyi-week-auto",
    name: "视频会员周卡 自动发货",
    type: "card",
    price: "6.90",
    faceValue: "7 天",
    stockState: "full",
    stockText: "库存充足",
    accent: "#00FFC3",
    delivery: "模拟支付成功后立即出卡",
  },
  {
    id: "p-002",
    categoryId: "iqiyi-month-low",
    name: "视频会员月卡 低库存批次",
    type: "card",
    price: "18.80",
    faceValue: "30 天",
    stockState: "low",
    stockText: "仅剩 3 张",
    accent: "#FFAB00",
    delivery: "库存边缘实时闪烁提醒",
  },
  {
    id: "p-003",
    categoryId: "valor-60-api",
    name: "手游点券 60 枚直充",
    type: "direct",
    price: "5.80",
    faceValue: "60 点券",
    stockState: "full",
    stockText: "API 正常",
    accent: "#59A8FF",
    delivery: "支付中展示 Blue Swirl",
  },
  {
    id: "p-004",
    categoryId: "global-wallet-100-manual",
    name: "海外钱包 100 USD 代充",
    type: "manual",
    price: "735.00",
    faceValue: "100 USD",
    stockState: "out",
    stockText: "人工排队",
    accent: "#FF3B30",
    delivery: "缺货/排队时降饱和玻璃",
  },
];
