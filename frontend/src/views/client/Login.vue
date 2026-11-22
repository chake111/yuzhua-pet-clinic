<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/client'
import { resolveDemoPreset } from '@/utils/demoPreset'
import { toast } from '@/utils/toast'
import LoginForm from '@/components/LoginForm.vue'

const store = useUserStore()
const route = useRoute()
const router = useRouter()
const demoPreset = computed(() => resolveDemoPreset('client', route.query.demo))
const initialUsername = computed(() => demoPreset.value?.username || '')
const initialPassword = computed(() => demoPreset.value?.password || '')

function onLoginSuccess() {
  toast.success('欢迎回来')
  router.replace(route.query.redirect || '/')
}
</script>

<template>
  <LoginForm
    :login="store.login"
    :on-success="onLoginSuccess"
    :initial-username="initialUsername"
    :initial-password="initialPassword"
  >
    <span>还没有账号？<router-link to="/register" class="lnk">注册一个</router-link></span>
    <router-link to="/doctors" class="lnk">先浏览医生</router-link>
    <template #switch>
      <router-link to="/admin/login" class="lnk">医生与管理员登录 <span aria-hidden="true">→</span></router-link>
    </template>
  </LoginForm>
</template>
