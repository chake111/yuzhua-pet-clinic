<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/client'
import { USER_GENDER, options } from '@/utils/dict'

const store = useUserStore()
const router = useRouter()
const el = ref(null)
const busy = ref(false)
const form = ref({ username: '', password: '', confirm: '', nickname: '', phone: '', gender: 1 })

// 原因: 校验正则与后端 AuthDto.Register 对齐，避免提交后才返回格式错误
const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]{3,19}$/, message: '以字母开头，4-20 位字母、数字或下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6-20 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再输入一次密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => (v === form.value.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur'
    }
  ],
  nickname: [{ required: true, max: 20, message: '请输入昵称（不超过 20 字）', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

async function submit() {
  if (!(await el.value.validate().catch(() => false))) return
  busy.value = true
  try {
    const { confirm, ...payload } = form.value
    await store.register(payload)
    ElMessage.success('注册成功，已自动登录')
    router.replace('/me/pets')
  } catch {
    // 原因: 请求层已提示失败原因，此处保留表单内容供修改重试
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="pc-narrow auth client-shell">
    <div class="pc-card">
      <h1>注册宠物主人账号</h1>
      <el-form ref="el" :model="form" :rules="rules" label-position="top" size="large" @keyup.enter="submit">
        <el-form-item label="账号" prop="username">
          <el-input v-model.trim="form.username" placeholder="字母开头，4-20 位" clearable />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model.trim="form.nickname" placeholder="医生看到的名字" clearable />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="form.phone" placeholder="请输入手机号" maxlength="11" clearable />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio v-for="o in options(USER_GENDER)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="6-20 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password placeholder="再输一次" />
        </el-form-item>
        <el-button type="primary" class="go" :loading="busy" @click="submit">注册并登录</el-button>
      </el-form>
      <div class="foot">
        已有账号？<router-link to="/login" class="lnk">直接登录</router-link>
        <router-link to="/" class="lnk">返回首页</router-link>
      </div>
    </div>
  </main>
</template>

<style scoped>
.auth {
  padding-top: 24px;
}

h1 {
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
}

.go {
  width: 100%;
}

.foot {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  font-size: var(--font-label);
  color: var(--color-muted);
}

/* 文字链接是 ink：这个系统里红字表示错误，不表示可点 */
.lnk {
  color: var(--color-ink);
  font-weight: 500;
}

@media (hover: hover) and (pointer: fine) {
  .lnk:hover { text-decoration: underline; }
}
.auth { max-width: 520px; }
.foot { flex-wrap: wrap; gap: 8px 16px; }
</style>
