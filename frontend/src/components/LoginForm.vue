<script setup>
import { reactive, ref, watch } from 'vue'
import EntryIcon from '@/components/EntryIcon.vue'
import LoginScene from '@/components/LoginScene.vue'

/**
 * 登录表单（用户端、后台登录页共用）：统一输入、校验、loading、
 * Enter 提交（中文输入法组合期忽略）。登录请求 login、欢迎语与成功跳转
 * onSuccess（拿到 store.login 的返回值，role 分流在页面回调里做）都由页面传入；
 * 表单下方链接区走默认插槽，两端切换入口走 #switch 插槽。不碰 Store。
 *
 * 采用原生表单元素 + Element Plus 同名样式类，彻底脱离 el-form / el-input 的
 * async-validator 与 Popper 运行时依赖，同时像素级复用 LoginScene 与 main.css 样式契约。
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
const errors = reactive({ username: '', password: '' })
const focused = ref('')
const showPwd = ref(false)
const busy = ref(false)

watch(() => props.initialUsername, (value) => {
  if (!edited.username) {
    form.value.username = value || ''
    if (form.value.username) errors.username = ''
  }
})
watch(() => props.initialPassword, (value) => {
  if (!edited.password) {
    form.value.password = value || ''
    if (form.value.password && form.value.password.length >= 6) errors.password = ''
  }
})

function validateField(field) {
  if (field === 'username') {
    errors.username = form.value.username ? '' : '请输入账号'
    return !errors.username
  }
  if (field === 'password') {
    errors.password = form.value.password && form.value.password.length >= 6 ? '' : '密码至少 6 位'
    return !errors.password
  }
  return true
}

function validate() {
  const okUser = validateField('username')
  const okPass = validateField('password')
  return okUser && okPass
}

function onBlur(field) {
  if (focused.value === field) focused.value = ''
  validateField(field)
}

function clearUsername() {
  edited.username = true
  form.value.username = ''
}

async function submit() {
  if (busy.value) return
  if (!validate()) return
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
    <form class="el-form el-form--large el-form--label-top" novalidate @submit.prevent="submit" @keydown.enter="onEnter">
      <div class="el-form-item el-form-item--large el-form-item--label-top is-required" :class="{ 'is-error': errors.username }">
        <label class="el-form-item__label" for="login-username">账号</label>
        <div class="el-form-item__content">
          <div class="el-input el-input--large el-input--prefix el-input--suffix">
            <div class="el-input__wrapper" :class="{ 'is-focus': focused === 'username' }">
              <span class="el-input__prefix">
                <span class="el-input__prefix-inner">
                  <EntryIcon name="User" />
                </span>
              </span>
              <input
                id="login-username"
                v-model.trim="form.username"
                class="el-input__inner"
                type="text"
                autocomplete="username"
                placeholder="请输入登录账号"
                @focus="focused = 'username'"
                @blur="onBlur('username')"
                @input="edited.username = true; if (errors.username) validateField('username')"
              />
              <span v-if="form.username" class="el-input__suffix">
                <span class="el-input__suffix-inner">
                  <button
                    type="button"
                    class="el-input__icon el-input__clear"
                    aria-label="清空账号"
                    tabindex="-1"
                    @mousedown.prevent
                    @click="clearUsername"
                  >
                    <EntryIcon name="CircleClose" />
                  </button>
                </span>
              </span>
            </div>
          </div>
          <div v-if="errors.username" class="el-form-item__error">{{ errors.username }}</div>
        </div>
      </div>

      <div class="el-form-item el-form-item--large el-form-item--label-top is-required" :class="{ 'is-error': errors.password }">
        <label class="el-form-item__label" for="login-password">密码</label>
        <div class="el-form-item__content">
          <div class="el-input el-input--large el-input--prefix el-input--suffix">
            <div class="el-input__wrapper" :class="{ 'is-focus': focused === 'password' }">
              <span class="el-input__prefix">
                <span class="el-input__prefix-inner">
                  <EntryIcon name="Lock" />
                </span>
              </span>
              <input
                id="login-password"
                v-model="form.password"
                class="el-input__inner"
                :type="showPwd ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
                @focus="focused = 'password'"
                @blur="onBlur('password')"
                @input="edited.password = true; if (errors.password) validateField('password')"
              />
              <span v-if="form.password" class="el-input__suffix">
                <span class="el-input__suffix-inner">
                  <button
                    type="button"
                    class="el-input__icon el-input__password"
                    :aria-label="showPwd ? '隐藏密码' : '显示密码'"
                    tabindex="-1"
                    @mousedown.prevent
                    @click="showPwd = !showPwd"
                  >
                    <EntryIcon :name="showPwd ? 'View' : 'Hide'" />
                  </button>
                </span>
              </span>
            </div>
          </div>
          <div v-if="errors.password" class="el-form-item__error">{{ errors.password }}</div>
        </div>
      </div>

      <button
        type="submit"
        class="el-button el-button--primary el-button--large go"
        :class="{ 'is-loading': busy, 'is-disabled': busy }"
        :disabled="busy"
      >
        <span>登录</span>
      </button>
    </form>
    <div v-if="$slots.default" class="foot"><slot /></div>
    <template #switch><slot name="switch" /></template>
  </LoginScene>
</template>
