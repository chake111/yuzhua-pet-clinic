<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  submitRequest: { type: Function, required: true },
  autocomplete: { type: Boolean, default: false }
})
defineOptions({ inheritAttrs: false })

const pwSaving = ref(false)
const pwRef = ref(null)

const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const pwRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度需为 6-20 位', trigger: 'blur' }
  ],
  confirm: [{
    validator: (_r, v, cb) => (v === pw.newPassword ? cb() : cb(new Error('两次输入的密码不一致'))),
    trigger: 'blur'
  }]
}

async function savePw() {
  if (!(await pwRef.value.validate().catch(() => false))) return
  pwSaving.value = true
  try {
    await props.submitRequest({ oldPassword: pw.oldPassword, newPassword: pw.newPassword })
    Object.assign(pw, { oldPassword: '', newPassword: '', confirm: '' })
    pwRef.value.resetFields()
    ElMessage.success('密码已更新')
  } finally {
    pwSaving.value = false
  }
}
</script>

<template>
  <el-form ref="pwRef" :model="pw" :rules="pwRules" v-bind="$attrs">
    <el-form-item label="原密码" prop="oldPassword">
      <el-input v-model="pw.oldPassword" type="password" show-password :autocomplete="autocomplete ? 'current-password' : undefined" />
    </el-form-item>
    <el-form-item label="新密码" prop="newPassword">
      <el-input v-model="pw.newPassword" type="password" show-password placeholder="6-20 位" :autocomplete="autocomplete ? 'new-password' : undefined" />
    </el-form-item>
    <el-form-item label="确认新密码" prop="confirm">
      <el-input v-model="pw.confirm" type="password" show-password :autocomplete="autocomplete ? 'new-password' : undefined" />
    </el-form-item>
  </el-form>
  <div class="ops">
    <el-button type="primary" :loading="pwSaving" @click="savePw">更新密码</el-button>
    <slot name="extra" />
  </div>
</template>

<style scoped>
.ops { display: flex; gap: 10px; }
</style>
