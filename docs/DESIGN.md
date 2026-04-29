# UI Design Specification

本项目后续 UI 设计与前端实现以 iOS 26 Liquid Glass 风格为视觉基准。适用范围包括用户端 H5、PC 自适应界面，以及需要更高质感表达的后台关键看板。

## 1. Material

### Liquid Glass

- 使用高透明液态玻璃材质，而不是传统厚重毛玻璃。
- 玻璃层应具备更强的背景折射感，依赖 `backdrop-filter`、透明遮罩、边缘高光和细微投影共同塑造层次。
- 正常态组件仅保留约 10% 白色半透遮罩。
- 背景内容需要能被隐约感知，避免把玻璃层做成不透明卡片。

推荐参数：

```css
background: rgba(255, 255, 255, 0.1);
backdrop-filter: blur(22px) saturate(180%) brightness(1.08);
-webkit-backdrop-filter: blur(22px) saturate(180%) brightness(1.08);
border: 0.5px solid rgba(255, 255, 255, 0.38);
box-shadow:
  inset 0 1px 0 rgba(255, 255, 255, 0.32),
  0 18px 48px rgba(15, 23, 42, 0.16);
```

### Dynamic Glow Border

- 组件边缘使用 0.5px 动态自适应高光。
- 高光应跟随 hover、press、focus 或状态变化产生流动感。
- 可通过伪元素、CSS 变量、径向渐变或 Framer Motion 动态控制光源位置。

## 2. Micro-interactions

### Liquid Feedback

- 所有点击动作必须包含液态反馈。
- 按下时组件应有轻微形变，表现出类似水滴张力的 Q 弹感。
- React 实现时优先使用 Framer Motion 的 spring 物理引擎。

推荐 motion 参数：

```ts
transition={{
  type: "spring",
  stiffness: 520,
  damping: 28,
  mass: 0.72
}}
whileTap={{
  scale: 0.975,
  borderRadius: "18px"
}}
```

### Layer Transition

- 层级切换时，背景模糊度应产生丝滑位移。
- 不使用简单淡入淡出作为唯一过渡。
- 切换时可联动 `blur`、`saturate`、`y`、`scale`、光源位置和透明度。

## 3. Status Visuals

### Normal

- 极致透明。
- 约 10% 白色半透遮罩。
- 边框保持轻微玻璃高光。

### Success

- 背后光源变为 `#00FFC3`。
- 带有脉冲呼吸感。
- 不把整个组件染成纯绿色，保持玻璃材质。

### Warning

- 组件边缘产生 `#FFAB00` 琥珀色光影折射。
- 适合库存预警、上游余额不足、订单异常提醒。

### Error

- 组件呈现 `#FF3B30` 深红折射。
- 伴随液态回弹震动动画。
- 动画应短促，避免影响用户继续操作。

## 4. Typography

- 使用动态字重。
- 当用户焦点落在某个区域时，该区域标题字重从 Regular 变为 Semibold。
- 不使用负字距。
- 移动端优先保证可读性和按钮文字不溢出。

推荐：

```css
font-weight: 400;
transition: font-weight 180ms ease;
```

聚焦态：

```css
font-weight: 600;
```

## 5. Code Implementation Rules

如果实现为代码：

- 使用 React + Tailwind CSS + Framer Motion 时，必须使用 `backdrop-filter` / `backdrop-blur`。
- 使用 Framer Motion spring 模拟 Q 弹交互。
- 点击、hover、focus、状态切换都应有可感知但克制的微交互。
- 所有状态色都应作为光源或折射使用，而不是简单纯色填充。

如果当前项目仍使用 Vue：

- 视觉规则保持一致。
- 动效可先用 CSS transition / keyframes 实现。
- 后续若新增 React 组件或独立营销页，再按 React + Tailwind CSS + Framer Motion 标准实现。
