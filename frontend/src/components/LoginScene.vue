<script setup>
import brandLogo from '@/assets/logo.svg'
import mascot from '@/assets/tuantuan.webp'
import { BRAND } from '@/brand.js'

defineProps({
  staff: Boolean
})
</script>

<template>
  <main class="login-scene">
    <header class="brand">
      <router-link to="/" :aria-label="BRAND.fullName + '首页'">
        <img class="brand-mark" :src="brandLogo" width="38" height="38" alt="" />
        <span class="brand-text">
          <b>{{ BRAND.name }}</b>
          <small>{{ BRAND.tagline }}</small>
        </span>
      </router-link>
      <span class="edition">{{ staff ? '医院工作台' : '宠物健康，从这里开始' }}</span>
    </header>

    <div class="stage">
      <div class="card-wrap">
        <div class="decor" aria-hidden="true">
          <span class="spark spark-one">✦</span>
          <span class="spark spark-two">✧</span>
          <svg class="paw" viewBox="0 0 64 64" fill="currentColor">
            <ellipse cx="15" cy="25" rx="7" ry="10" transform="rotate(-25 15 25)" />
            <ellipse cx="29" cy="15" rx="7" ry="10" />
            <ellipse cx="45" cy="19" rx="7" ry="10" transform="rotate(20 45 19)" />
            <ellipse cx="55" cy="33" rx="6" ry="9" transform="rotate(35 55 33)" />
            <path d="M18 45c0-9 10-18 17-18s17 13 17 20c0 12-12 6-17 6s-17 5-17-8Z" />
          </svg>
        </div>
        <img class="mascot" :src="mascot" width="1020" height="510" alt="吉祥物团团，一只戴着珊瑚色项圈、垂耳和前爪搭在卡片边缘的小狗" fetchpriority="high" />
        <section class="login-card" aria-labelledby="login-heading">
          <div class="heading">
            <span class="eyebrow">{{ staff ? '医生 · 管理员' : '宠物主人' }}</span>
            <h1 id="login-heading">{{ staff ? '开启今天的守护' : '欢迎回来' }}</h1>
            <p>{{ staff ? '登录医院工作台，照顾每一份信任。' : '陪伴它的每一天，都值得用心照顾。' }}</p>
          </div>
          <slot />
        </section>
        <footer class="switch-entry"><slot name="switch" /></footer>
      </div>
    </div>
  </main>
</template>

<style scoped>
.login-scene {
  --login-paper: #faf8f5;
  --login-accent: #be9972;
  /* 两端登录页共用本组件，而 /admin/login 不挂 body.client-mode，
     占位色不能只靠全局那一条，这里必须自己声明——但值走令牌，不写字面值 */
  --el-text-color-placeholder: var(--color-muted-soft);
  --el-border-color: var(--color-border-strong);
  min-height: 100vh;
  min-height: 100svh;
  display: flex;
  flex-direction: column;
  background: var(--login-paper);
  color: var(--color-ink);
  padding: 28px 40px 20px;
}
.brand { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.brand a { display: inline-flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; }
.brand-text { display: flex; flex-direction: column; min-width: 0; line-height: 1.25; }
.brand-text b { font-size: 16px; font-weight: 600; }
.brand-text small { font-size: 12px; font-weight: 400; color: var(--color-muted); white-space: nowrap; }
.brand-mark { width: 38px; height: 38px; display: block; flex: none; }
.edition { font-size: 14px; color: var(--color-muted); }
.stage { flex: 1; display: grid; place-items: center; padding: 150px 0 28px; }
.card-wrap { position: relative; width: min(100%, 440px); }
.login-card { position: relative; padding: 48px 36px 28px; border: 1px solid #e7e1da; border-radius: var(--radius-lg); background: var(--color-canvas); box-shadow: 0 16px 48px rgb(74 51 27 / 5%); }
.mascot { position: absolute; z-index: 2; width: 340px; height: auto; top: 0; left: 50%; transform: translate(-50%, -73.3%); pointer-events: none; }
.heading { margin-bottom: 26px; }
.eyebrow { color: var(--color-muted); font-size: 13px; letter-spacing: .12em; }
h1 { margin: 8px 0 10px; font-size: 28px; line-height: 1.3; font-weight: 650; letter-spacing: -.03em; }
.heading p { margin: 0; font-size: 14px; line-height: 1.7; color: var(--color-muted); }
.switch-entry { margin-top: 20px; text-align: center; font-size: 14px; }
.decor { pointer-events: none; }
.spark { position: absolute; color: var(--login-accent); }
.spark-one { left: 22px; top: -93px; font-size: 30px; transform: rotate(15deg); }
.spark-two { right: 28px; top: -125px; font-size: 22px; }
.paw { position: absolute; width: 58px; left: -90px; top: 250px; color: #e7ded1; transform: rotate(-24deg); }
:deep(.el-form-item) { margin-bottom: 24px; }
:deep(.el-form-item__label) { font-size: 14px; color: var(--color-body); }
:deep(.el-input__wrapper) { min-height: 48px; border-radius: var(--radius-sm); }
:deep(.el-input__inner) { font-size: 16px; }
:deep(.el-input__inner:focus-visible) { outline: none; }
:deep(.go) { width: 100%; min-height: 48px; font-size: 16px; font-weight: 600; transition: transform .16s var(--ease), background-color .16s var(--ease); }
:deep(.go:active) { transform: scale(.985); }
:deep(.foot) { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-top: 20px; color: var(--color-muted); font-size: 14px; }
:deep(.lnk) { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; color: var(--color-ink); text-underline-offset: 4px; }
:deep(.lnk:hover) { text-decoration: underline; }
:deep(a:focus-visible) { outline: 2px solid var(--color-ink); outline-offset: 4px; border-radius: 4px; }
@media (prefers-reduced-motion: no-preference) {
  .heading { animation: arrive .45s var(--ease) both; }
  .spark-two { animation: glint 5s ease-in-out infinite; }
}
@keyframes arrive { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }
@keyframes glint { 0%, 100% { opacity: .55; } 50% { opacity: 1; } }
@media (max-width: 600px) {
  .login-scene { padding: 20px 20px max(20px, env(safe-area-inset-bottom)); }
  .brand { justify-content: center; }
  .brand a { font-size: 15px; }
  .brand-text b { font-size: 15px; }
  .edition { display: none; }
  .stage { padding-top: 142px; }
  .login-card { padding: 42px 24px 24px; }
  .mascot { width: min(300px, 100%); }
  .spark-one { left: 8px; top: -78px; font-size: 23px; }
  .spark-two { right: 12px; top: -117px; }
  .paw { display: none; }
  h1 { font-size: 26px; }
}
</style>
