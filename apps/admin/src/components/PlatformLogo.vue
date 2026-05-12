<script setup lang="ts">
defineProps<{
  name: string
}>()
</script>

<template>
  <span class="platform-logo" :class="`logo-${name}`" aria-hidden="true">
    <svg v-if="name === 'douyin'" viewBox="0 0 32 32">
      <path d="M18.4 6v13.4a6.1 6.1 0 1 1-4.2-5.8v4.1a2.3 2.3 0 1 0 1 1.9V6h3.2Z" />
      <path d="M18.4 6c1 3.7 3.2 5.8 6.8 6.4v4c-2.6-.2-4.9-1.1-6.8-2.8" />
    </svg>
    <svg v-else-if="name === 'taobao'" viewBox="0 0 32 32">
      <path d="M6 9h20v14H6z" />
      <path d="M10 13h12M10 18h9" />
    </svg>
    <svg v-else-if="name === 'pdd'" viewBox="0 0 32 32">
      <path d="M8 9h16l2 5-10 10L6 14z" />
      <path d="M12 14h8M16 10v12" />
    </svg>
    <svg v-else-if="name === 'xianyu'" viewBox="0 0 32 32">
      <path d="M6 17c4.2-4.4 9-4.8 15.1-1.3l4.9-3v9l-4.9-3C15 22.2 10.2 21.8 6 17Z" />
      <circle cx="12" cy="16.5" r="1.2" />
    </svg>
    <svg v-else-if="name === 'xiaohongshu'" viewBox="0 0 32 32">
      <path d="M8 8h16v16H8z" />
      <path d="M12 13h8M12 18h8" />
    </svg>
    <svg v-else-if="name === 'unlimited'" viewBox="0 0 32 32">
      <path d="M7 16c3.2-6.2 7.1-6.2 11.2 0 2.6 3.9 5 4 6.8 0" />
      <path d="M7 16c3.2 6.2 7.1 6.2 11.2 0 2.6-3.9 5-4 6.8 0" />
    </svg>
    <svg v-else-if="name === 'h5'" viewBox="0 0 32 32">
      <path d="M11 5h10a2 2 0 0 1 2 2v18a2 2 0 0 1-2 2H11a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z" />
      <path d="M14 23h4M13 10h6M13 15h6" />
    </svg>
    <svg v-else-if="name === 'web' || name === 'pc'" viewBox="0 0 32 32">
      <path d="M5 8h22v14H5z" />
      <path d="M12 26h8M16 22v4" />
      <path d="M10 13h12M10 17h8" />
    </svg>
    <svg v-else-if="name === 'api'" viewBox="0 0 32 32">
      <path d="M11 12H8a4 4 0 0 0 0 8h3M21 12h3a4 4 0 0 1 0 8h-3" />
      <path d="M12 18h8M14 14l-4 8M22 14l-4 8" />
    </svg>
    <svg v-else-if="name === 'private'" viewBox="0 0 32 32">
      <path d="M8 14V9l8-4 8 4v5c0 5.4-3.2 10.2-8 12-4.8-1.8-8-6.6-8-12Z" />
      <path d="M12 16l3 3 6-7" />
    </svg>
    <svg v-else viewBox="0 0 32 32">
      <path d="M7 10h18v12H7z" />
      <path d="M11 14h10M11 18h7" />
    </svg>
  </span>
</template>

<style scoped>
.platform-logo {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  color: rgba(225, 232, 242, 0.5);
  background: rgba(150, 165, 184, 0.09);
  font-size: 13px;
  font-weight: 800;
}

.platform-logo svg {
  width: 18px;
  height: 18px;
  display: block;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.platform-logo.logo-douyin {
  color: #f4fbff;
  background: linear-gradient(135deg, #15151d, #05060a);
  box-shadow: inset 7px 0 0 rgba(0, 242, 234, 0.9), inset -7px 0 0 rgba(255, 0, 80, 0.82);
}

.platform-logo.logo-taobao {
  color: #fff4df;
  background: linear-gradient(135deg, #ff7a00, #ff3d00);
}

.platform-logo.logo-pdd {
  color: #fff3f3;
  background: linear-gradient(135deg, #e60012, #ff5b5b);
}

.platform-logo.logo-xianyu {
  color: #08201f;
  background: linear-gradient(135deg, #ffe85b, #18d4c1);
}

.platform-logo.logo-xiaohongshu {
  color: #fff8f8;
  background: linear-gradient(135deg, #ff2442, #d90f2f);
}

.platform-logo.logo-unlimited {
  color: #082019;
  background: linear-gradient(135deg, #b9fff0, #00ffc3);
}

.platform-logo.logo-h5 {
  color: #eef7ff;
  background: linear-gradient(135deg, #2563eb, #06b6d4);
}

.platform-logo.logo-web,
.platform-logo.logo-pc {
  color: #f7fbff;
  background: linear-gradient(135deg, #475569, #111827);
}

.platform-logo.logo-api {
  color: #fff7ed;
  background: linear-gradient(135deg, #f97316, #7c2d12);
}

.platform-logo.logo-private {
  color: #f0fdf4;
  background: linear-gradient(135deg, #16a34a, #064e3b);
}
</style>
