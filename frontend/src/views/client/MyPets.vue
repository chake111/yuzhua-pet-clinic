<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { pets, removePet } from '@/api/user'
import { PET_GENDER, DEWORM, VACCINE, tagType, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import PetForm from '@/components/PetForm.vue'
import StatusChip from '@/components/StatusChip.vue'

const rows = ref([])
const failed = ref(false)
const loading = ref(false)
const open = ref(false)
const editing = ref(null)

async function load() {
  loading.value = true
  failed.value = false
  try {
    rows.value = (await pets()) || []
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function add() {
  editing.value = null
  open.value = true
}

function edit(p) {
  editing.value = p
  open.value = true
}

async function del(p) {
  const ok = await ElMessageBox.confirm(
    `删除「${p.name}」的档案？历史预约和病历上的记录会保留。`, '删除宠物',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await removePet(p.id)
  ElMessage.success('已删除')
  load()
}

function age(p) {
  if (p.ageMonth == null) return '月龄未填'
  const years = Math.floor(p.ageMonth / 12)
  const months = p.ageMonth % 12
  return [years ? `${years} 岁` : '', months || !years ? `${months} 个月` : ''].filter(Boolean).join(' ')
}

onMounted(() => loadWithSavedScroll(load))
</script>

<template>
  <div v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <header class="head">
      <div class="heading">
        <h1 class="pc-title">宠物档案</h1>
        <span v-if="!loading" class="pc-muted">{{ rows.length }} 只宠物</span>
      </div>
      <el-button v-if="rows.length" type="primary" size="small" @click="add">
        <el-icon><Plus /></el-icon><span>添加宠物</span>
      </el-button>
    </header>

    <div class="list">
      <article v-for="p in rows" :key="p.id" class="pc-card pet">
        <header class="identity">
          <el-avatar :size="56" :src="p.avatar" shape="square" class="avatar" alt="">{{ p.name?.[0] }}</el-avatar>
          <div class="info">
            <h2>{{ p.name }}</h2>
            <p class="breed pc-muted">{{ p.species }} · {{ p.breed || '品种未填' }}</p>
          </div>
        </header>

        <dl class="facts">
          <div><dt>年龄</dt><dd>{{ age(p) }}</dd></div>
          <div><dt>体重</dt><dd>{{ p.weight != null ? `${p.weight} kg` : '未填写' }}</dd></div>
          <div><dt>性别</dt><dd>{{ text(PET_GENDER, p.gender) }}<span v-if="p.isNeutered === 1"> · 已绝育</span></dd></div>
        </dl>

        <div class="tags">
          <StatusChip :tone="tagType(VACCINE, p.vaccineStatus)">疫苗：{{ text(VACCINE, p.vaccineStatus) }}</StatusChip>
          <StatusChip :tone="tagType(DEWORM, p.dewormFlag)">驱虫：{{ text(DEWORM, p.dewormFlag) }}</StatusChip>
        </div>
        <div v-if="p.allergy" class="warn">
          <b>过敏史</b><p>{{ p.allergy }}</p>
        </div>
        <div v-if="p.medicalHistory" class="history">
          <b>既往病史</b><p>{{ p.medicalHistory }}</p>
        </div>

        <footer class="footer">
          <span class="pc-muted born">建档于 {{ toDate(p.createTime) }}</span>
          <div class="ops">
            <el-button class="delete" text type="danger" :aria-label="`删除${p.name}的档案`" @click="del(p)">删除</el-button>
            <el-button plain :aria-label="`编辑${p.name}的档案`" @click="edit(p)">编辑档案</el-button>
          </div>
        </footer>
      </article>

      <el-empty v-if="!failed && !loading && !rows.length" description="还没有宠物档案">
        <el-button type="primary" @click="add">添加第一只宠物</el-button>
      </el-empty>
    </div>

    <PetForm v-model="open" :pet="editing" @saved="load" />
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 24px 0 16px; }
.heading { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.heading h1.pc-title { margin: 0; line-height: 1.5; }
.head .el-button { flex-shrink: 0; gap: 4px; }
.list { display: grid; gap: 16px; }
.pet { min-width: 0; padding: 20px; }
.identity { display: flex; align-items: center; gap: 14px; }
.avatar { flex-shrink: 0; border-radius: var(--radius-md); }
.info { min-width: 0; }
.info h2 { margin: 0; font-size: 18px; line-height: 1.5; overflow-wrap: anywhere; }
.breed { margin: 4px 0 0; line-height: 1.6; overflow-wrap: anywhere; }
.facts { display: grid; grid-template-columns: 1fr 1fr 1.3fr; gap: 12px; margin: 14px 0; }
.facts dt { color: var(--color-muted); font-size: var(--font-label); margin-bottom: 5px; }
.facts dd { margin: 0; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }
.tags { display: flex; gap: 8px; flex-wrap: wrap; }
.tags :deep(.chip) { font-size: var(--font-label); height: auto; min-height: 24px; white-space: normal; padding-block: 3px; }
.warn { margin-top: 12px; font-size: var(--font-reading); color: var(--color-warning); background: var(--color-warning-soft); border-radius: var(--radius-sm); padding: 12px; }
.history { margin-top: 12px; font-size: var(--font-reading); color: var(--color-body); }
.warn b, .history b { font-weight: 600; }
.warn p, .history p { margin: 4px 0 0; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.footer { display: flex; align-items: center; justify-content: space-between; gap: 8px 16px; flex-wrap: wrap; margin-top: 14px; padding-top: 10px; border-top: 1px solid var(--color-hairline-soft); }
.born { font-size: var(--font-meta); }
.ops { margin-left: auto; display: flex; align-items: center; gap: 12px; }
.ops .el-button { margin: 0; }
.ops .el-button.is-plain { color: var(--color-ink); }
.ops .delete { font-size: 14px; font-weight: 400; }
@media (max-width: 420px) {
  .pet { padding: 16px; }

}
@media (max-width: 359px) {
  .born { flex-basis: 100%; }
}
</style>
