<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createPet, updatePet } from '@/api/user'
import { PET_GENDER, SPECIES, VACCINE, options } from '@/utils/dict'
import ImageUpload from '@/components/ImageUpload.vue'

const props = defineProps({ modelValue: Boolean, pet: Object })
const emit = defineEmits(['update:modelValue', 'saved'])

const blank = () => ({
  name: '', species: SPECIES[0], breed: '', gender: 1, birthday: '', weight: null,
  color: '', isNeutered: 0, vaccineStatus: 0, dewormFlag: 0, avatar: '',
  allergy: '', medicalHistory: '', remark: ''
})

const form = reactive(blank())
const ref0 = ref(null)
const busy = ref(false)

// 原因: 仅提交可编辑档案字段，不回传 id、userId 与系统维护时间
const EDITABLE = [
  'name', 'species', 'breed', 'gender', 'birthday', 'ageMonth', 'weight', 'color',
  'isNeutered', 'vaccineStatus', 'dewormFlag', 'avatar', 'allergy', 'medicalHistory', 'remark'
]
const rules = {
  name: [
    { required: true, message: '请填写宠物昵称', trigger: 'blur' },
    { max: 20, message: '宠物昵称不超过 20 字', trigger: 'blur' }
  ],
  species: [{ required: true, message: '请选择宠物种类', trigger: 'change' }]
}

watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(form, blank(), props.pet || {})
  ref0.value?.clearValidate()
})

async function submit() {
  if (busy.value) return
  busy.value = true
  try {
    if (!(await ref0.value.validate().catch(() => false))) return
    const payload = Object.fromEntries(EDITABLE.map(key => [key, form[key]]))
    // 原因: 后端约定显式 null 表示清空字段，省略键才表示保持原值
    payload.birthday = payload.birthday || null
    payload.weight = payload.weight ?? null
    props.pet?.id ? await updatePet(props.pet.id, payload) : await createPet(payload)
    ElMessage.success(props.pet?.id ? '档案已更新' : '宠物档案已建好')
    emit('saved')
    emit('update:modelValue', false)
  } catch {
    // 原因: 请求层已提示失败原因，保留表单输入供重试
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="pet?.id ? '编辑宠物档案' : '添加宠物'"
             width="min(520px, 92vw)" top="6vh" append-to-body
             @update:model-value="emit('update:modelValue', $event)">
    <el-form ref="ref0" :model="form" :rules="rules" label-position="top">
      <el-row :gutter="12">
        <el-col :xs="24" :sm="12">
          <el-form-item label="头像">
            <ImageUpload v-model="form.avatar" :max="1" label="宠物照片" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12"><el-form-item label="昵称" prop="name"><el-input v-model="form.name" maxlength="20" /></el-form-item></el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="种类" prop="species">
            <el-select v-model="form.species" class="full">
              <el-option v-for="s in SPECIES" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12"><el-form-item label="品种"><el-input v-model="form.breed" placeholder="如 英短 / 金毛" maxlength="30" /></el-form-item></el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="性别">
            <el-radio-group v-model="form.gender">
              <el-radio v-for="o in options(PET_GENDER)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="生日">
            <el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD"
                            placeholder="请选择出生日期" class="full" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12"><el-form-item label="体重(kg)"><el-input-number v-model="form.weight" :min="0" :max="200" :precision="2" :step="0.5" class="full" controls-position="right" /></el-form-item></el-col>
        <el-col :xs="24" :sm="12"><el-form-item label="毛色"><el-input v-model="form.color" maxlength="20" /></el-form-item></el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="绝育">
            <el-switch v-model="form.isNeutered" :active-value="1" :inactive-value="0" active-text="已绝育" aria-label="绝育" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="疫苗">
            <el-select v-model="form.vaccineStatus" class="full">
              <el-option v-for="o in options(VACCINE)" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="驱虫">
            <el-switch v-model="form.dewormFlag" :active-value="1" :inactive-value="0" active-text="已驱虫" aria-label="驱虫" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="过敏史"><el-input v-model="form.allergy" type="textarea" :rows="2" maxlength="200" show-word-limit /></el-form-item>
      <el-form-item label="既往病史"><el-input v-model="form.medicalHistory" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" maxlength="200" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full { width: 100%; }
:deep(.el-date-editor.el-input) { width: 100%; }
:deep(.el-radio-group) { flex-wrap: wrap; }
</style>
