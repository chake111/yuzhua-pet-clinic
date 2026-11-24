<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import ChangePasswordForm from '@/components/ChangePasswordForm.vue'

const store = useAdminStore()
const router = useRouter()
const me = ref(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    me.value = await store.refresh()
  } finally {
    loading.value = false
  }
}

function logout() {
  store.logout()
  router.replace('/admin/login')
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <section class="pc-card">
      <div class="id">
        <el-avatar :size="54" :src="me?.avatar" class="ava">{{ me?.name?.[0] }}</el-avatar>
        <div>
          <b class="nm">{{ me?.name }}</b>
          <p class="pc-muted">
            {{ store.isAdmin ? '管理员' : '医生' }}
          </p>
        </div>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="登录账号">{{ me?.username }}</el-descriptions-item>
        <el-descriptions-item label="账号编号">{{ me?.id }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ me?.phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ me?.email || '—' }}</el-descriptions-item>
        <template v-if="store.isDoctor">
          <el-descriptions-item label="医生档案">{{ me?.doctorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属科室">{{ me?.departmentName || '—' }}</el-descriptions-item>
        </template>
      </el-descriptions>
    </section>

    <section class="pc-card">
      <h3>修改密码</h3>
      <ChangePasswordForm class="pw" label-position="top" autocomplete :submit-request="changePassword">
        <template #extra>
          <el-button @click="logout">退出登录</el-button>
        </template>
      </ChangePasswordForm>
    </section>
  </div>
</template>

<style scoped>
.page { max-width: 760px; }
.id { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.ava { background: var(--color-primary); color: #fff; font-size: 20px; }
.nm { font-size: 17px; }
.id p { margin: 2px 0 0; font-size: 12px; }
h3 { margin: 0 0 14px; font-size: 15px; }
.pw :deep(.el-form-item) { margin-bottom: 14px; }
.pw { max-width: 340px; }
</style>
