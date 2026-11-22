<script setup>
import { ref, watch } from 'vue'
import { Lock, User } from '@element-plus/icons-vue'
import LoginScene from '@/components/LoginScene.vue'

/**
 * 登录表单（用户端、后台登录页共用）：统一输入、校验、loading、
 * Enter 提交（中文输入法组合期忽略）。登录请求 login、欢迎语与成功跳转
 * onSuccess（拿到 store.login 的返回值，role 分流在页面回调里做）都由页面传入；
 * 表单下方链接区走默认插槽，两端切换入口走 #switch 插槽。不碰 Store。
 */
const props = defineProps({
  login: { type: Function, required: true },
  onSuccess: { type: Function, required: true },
  staff: { type: Boolean, default: false },
  initialUsername: { type: String, default: '' },
  initialPassword: { type: String, default: '' }
})

const form = ref({
  username: props.initialUsername || '',
  password: props.initialPassword || ''
})
const edited = { username: false, password: false }
const el = ref(null)
const busy = ref(false)

watch(() => props.initialUsername, (value) => {
  if (!edited.username) form.value.username = value || ''
})
watch(() => props.initialPassword, (value) => {
  if (!edited.password) form.value.password = value || ''
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '密码至少 6 位', trigger: 'blur' }]
}

async function submit() {
  if (busy.value) return
  if (!(await el.value.validate().catch(() => false))) return
  busy.value = true
  try {
    const vo = await props.login(form.value)
    props.onSuccess(vo)
  } catch {
    /* 失败原因请求层已提示 */
  } finally {
    busy.value = false
  }
}

function onEnter(event) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  submit()
}
</script>

<template>
  <LoginScene :staff="staff">
    <el-form ref="el" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="submit" @keydown.enter="onEnter">
      <el-form-item label="账号" prop="username">
        <el-input v-model.trim="form.username" autocomplete="username" placeholder="请输入登录账号" clearable @input="edited.username = true" @clear="edited.username = true">
          <template #prefix><el-icon><User /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入密码" show-password @input="edited.password = true">
          <template #prefix><el-icon><Lock /></el-icon></template>
        </el-input>
      </el-form-item>
      <el-button type="primary" class="go" :loading="busy" native-type="submit">登录</el-button>
    </el-form>
    <div v-if="$slots.default" class="foot"><slot /></div>
    <template #switch><slot name="switch" /></template>
  </LoginScene>
</template>
