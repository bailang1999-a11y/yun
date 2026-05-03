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
</style>
