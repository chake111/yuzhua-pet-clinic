<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword, updateProfile } from '@/api/user'
import { useUserStore } from '@/stores/client'
import { options, USER_GENDER } from '@/utils/dict'
import { toDate } from '@/utils/format'
import ChangePasswordForm from '@/components/ChangePasswordForm.vue'
import ImageUpload from '@/components/ImageUpload.vue'

const store = useUserStore()
const saving = ref(false)

const form = reactive({
  nickname: '', avatar: '', phone: '', email: '', gender: 0, city: '', address: ''
})

const rules = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }, { max: 20, message: '昵称不超过 20 字', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}
const ref0 = ref(null)

const PROFILE_FIELDS = ['avatar', 'phone', 'email', 'city', 'address']

function fill(u) {
  const data = u || {}
  const patch = { nickname: data.nickname || data.name || '', gender: data.gender ?? 0 }
  for (const key of PROFILE_FIELDS) patch[key] = data[key] || ''
  Object.assign(form, patch)
}

async function save() {
  if (!(await ref0.value.validate().catch(() => false))) return
  saving.value = true
  try {
    await updateProfile({ ...form, gender: Number(form.gender) })
    await store.refresh()
    fill(store.user)
    ElMessage.success('资料已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const me = await store.refresh().catch(() => store.user)
  fill(me)
})
</script>

<template>
  <div class="wrap">
    <h1 class="pc-title">账号设置</h1>
    <section class="pc-card">
      <h3>基本资料</h3>
      <el-form ref="ref0" :model="form" :rules="rules" label-width="72px" label-position="top" size="small">
        <el-form-item label="头像">
          <ImageUpload v-model="form.avatar" :max="1" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12"><el-form-item label="昵称" prop="nickname"><el-input v-model="form.nickname" maxlength="20" /></el-form-item></el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="性别">
              <el-radio-group v-model="form.gender">
                <el-radio v-for="o in options(USER_GENDER)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" maxlength="11" /></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="邮箱" prop="email"><el-input v-model="form.email" maxlength="60" /></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="城市"><el-input v-model="form.city" maxlength="30" /></el-form-item></el-col>
          <el-col :xs="24" :sm="12"><el-form-item label="地址"><el-input v-model="form.address" maxlength="120" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <p class="pc-muted tip">
        登录账号 {{ store.user?.username }} 注册于 {{ toDate(store.user?.createTime) }}。账号名注册后不可修改。
      </p>
      <el-button type="primary" :loading="saving" @click="save">保存资料</el-button>
    </section>

    <section class="pc-card">
      <h3>修改密码</h3>
      <ChangePasswordForm label-width="72px" label-position="top" size="small" :submit-request="changePassword" />
    </section>
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; gap: 12px; }
h3 { margin: 0 0 16px; font-size: 16px; font-weight: 600; color: var(--color-ink); }
.tip { font-size: var(--font-label); line-height: 1.6; margin: 4px 0 12px; }
:deep(.el-form-item) { margin-bottom: 16px; }
.wrap > h1 { margin-bottom: 0; }
.tip { overflow-wrap: anywhere; }
:deep(.el-form-item__label) { font-size: 14px; margin-bottom: 6px; }
</style>
