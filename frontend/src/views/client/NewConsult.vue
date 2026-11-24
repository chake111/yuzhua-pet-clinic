<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createConsult, doctorDetail, pets } from '@/api/user'
import ImageUpload from '@/components/ImageUpload.vue'
import PetForm from '@/components/PetForm.vue'
import PetPicker, { pickAfterLoad } from '@/components/PetPicker.vue'
import StatusChip from '@/components/StatusChip.vue'

const route = useRoute()
const router = useRouter()
const doctorId = Number(route.params.doctorId)
const doctor = ref(null)
const myPets = ref([])
const failed = ref(false)
const loading = ref(true)
const busy = ref(false)
const petOpen = ref(false)

const form = reactive({ petId: null, symptom: '', images: '' })

async function loadPets(keep) {
  myPets.value = (await pets()) || []
  form.petId = pickAfterLoad(myPets.value, form.petId, keep)
}

async function submit() {
  if (busy.value) return
  if (!form.petId) return ElMessage.warning('请选择问诊宠物')
  if (!form.symptom.trim()) return ElMessage.warning('请描述症状')
  busy.value = true
  try {
    const s = await createConsult({ ...form, doctorId, symptom: form.symptom.trim() })
    ElMessage.success('已发起，等待医生接诊')
    router.replace(`/consult/${s.id}`)
  } catch {
    // 原因: 请求层已提示失败原因，此处保留表单输入供重试
  } finally {
    busy.value = false
  }
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [d] = await Promise.all([doctorDetail(doctorId), loadPets()])
    doctor.value = d
    if (d && d.status !== 1) ElMessage.warning('该医生目前停诊，暂时无法发起图文问诊')
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)

</script>

<template>
  <div class="pc-narrow has-cta" v-loading="loading">
    <h1 class="pc-title">发起图文问诊</h1>
    <LoadFailure v-if="failed" @retry="load" />
    <el-empty v-else-if="!loading && !doctor" description="暂无医生信息" />
    <template v-else-if="doctor">

    <section class="pc-card doc">
      <el-avatar :size="44" :src="doctor.avatar" alt="">{{ doctor.name?.[0] }}</el-avatar>
      <div class="d1">
        <b>{{ doctor.name }}</b>
        <span class="pc-muted">{{ doctor.title }} {{ doctor.departmentName }}</span>
      </div>
      <StatusChip tone="success">免费</StatusChip>
    </section>

    <section class="pc-card blk">
      <PetPicker v-model="form.petId" :pets="myPets" radio-name="consult-pet" title="问诊宠物" @add="petOpen = true" />
    </section>

    <section class="pc-card blk">
      <h2 class="bh-t">症状描述<span class="pc-muted required">必填</span></h2>
      <el-input v-model="form.symptom" type="textarea" aria-label="症状描述" aria-required="true" :rows="3" maxlength="500" show-word-limit
                placeholder="何时开始不适？有哪些症状？是否用过药？"
                @focus="event => event.target.scrollIntoView({ block: 'center' })" />
      <h2 class="bh-t">患处照片<span class="pc-muted required">选填</span></h2>
      <ImageUpload v-model="form.images" :max="6" />
    </section>

    <div class="cta-bar pc-cta-bar">
      <div class="sum">
        <span class="pc-muted">图文问诊</span>
        <b>免费</b>
      </div>
      <el-button type="primary" size="large" class="go" :loading="busy" @click="submit">发起问诊</el-button>
    </div>

    <PetForm v-model="petOpen" @saved="loadPets(true)" />
    </template>
  </div>
</template>

<style scoped>
.doc { display: flex; align-items: center; gap: 12px; }
.d1 { flex: 1; display: flex; flex-direction: column; }
.d1 b { font-size: 16px; font-weight: 600; }
.blk { margin-top: 12px; }
.bh-t { display: block; margin-bottom: 12px; }
.bh-t { margin: 0; font-size: 16px; font-weight: 600; line-height: 1.5; }
.bh-t { margin-bottom: 12px; }

.sub { margin: 16px 0 8px; font-size: var(--font-label); color: var(--color-muted); }

/* sticky CTA 条坐在 tab bar 之上，不是屏幕底部。
   位置、宽度上限与白底投影走公共 .pc-cta-bar；这里留高度与内部排布。 */
.cta-bar {
  height: var(--cta-bar-height);
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
}
.sum { display: flex; align-items: baseline; gap: 4px; }
.sum b { font-size: 22px; font-weight: 700; }
.cta-bar .go { margin-left: auto; min-width: 144px; }
.d1 { min-width: 0; gap: 4px; overflow-wrap: anywhere; }
.required { margin-left: 8px; font-weight: 400; }

</style>
