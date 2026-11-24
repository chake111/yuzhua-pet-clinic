<script setup>
import { Plus, Select } from '@element-plus/icons-vue'
import { PET_GENDER, text } from '@/utils/dict'

defineProps({
  pets: { type: Array, required: true },
  modelValue: { type: Number, default: null },
  radioName: { type: String, required: true },
  title: { type: String, required: true },
  error: { type: String, default: '' },
  errorId: { type: String, default: '' }
})
defineEmits(['update:modelValue', 'add'])

function ageText(p) {
  return (p.ageMonth == null || (!p.birthday && p.ageMonth === 0)) ? '月龄未填' : `${p.ageMonth} 个月`
}
</script>

<script>
export function pickAfterLoad(list, currentId, keep) {
  if (keep && list.some((p) => p.id === currentId)) return currentId
  return list.length === 1 ? list[0].id : null
}
</script>

<template>
  <header class="bh">
    <h2>{{ title }}</h2>
    <el-button v-if="pets.length" link @click="$emit('add')">
      <el-icon><Plus /></el-icon>&nbsp;添加宠物
    </el-button>
  </header>
  <p v-if="error" :id="errorId" class="field-error" role="alert">{{ error }}</p>
  <div v-if="pets.length" class="pets" role="radiogroup" :aria-label="title"
       :aria-invalid="errorId ? !!error : undefined" :aria-describedby="errorId && error ? errorId : undefined">
    <label v-for="p in pets" :key="p.id" class="pet" :class="{ on: modelValue === p.id }">
      <input :checked="modelValue === p.id" type="radio" :name="radioName" :value="p.id" class="pet-radio"
             :aria-label="p.name" @change="$emit('update:modelValue', p.id)" />
      <el-avatar :size="38" shape="square" :src="p.avatar" class="pa" alt="">{{ p.name?.[0] }}</el-avatar>
      <span class="pinfo">
        <span class="pn">{{ p.name }}</span>
        <span class="pd">{{ p.species }} {{ text(PET_GENDER, p.gender) }} {{ ageText(p) }}</span>
      </span>
      <span class="mark" :class="{ on: modelValue === p.id }" aria-hidden="true">
        <el-icon v-if="modelValue === p.id"><Select /></el-icon>
      </span>
    </label>
  </div>
  <el-empty v-else :image-size="60" description="还没有宠物档案，先添加一只">
    <el-button type="primary" plain @click="$emit('add')">添加宠物</el-button>
  </el-empty>
</template>

<style scoped>
.bh { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.bh h2 { margin: 0; font-size: 16px; font-weight: 600; line-height: 1.5; }

/* 单列列表 + 细分隔线：宠物选项是卡片里的内容，再给每行描边会读成卡中卡。
   宽屏保持同一套行结构，不换成双列灰底小块——同一个组件不该随断点换容器语言。 */
.pets { display: grid; grid-template-columns: minmax(0, 1fr); margin: 8px 0 0; }
.pet {
  position: relative; min-height: 68px; padding: 10px 8px; cursor: pointer;
  display: flex; align-items: center; gap: 12px; border-radius: var(--radius-sm);
  transition: background-color .18s var(--ease), color .18s var(--ease);
}
.pet + .pet::before {
  content: ''; position: absolute; top: 0; left: 8px; right: 8px; height: 1px;
  background: var(--color-hairline-soft);
}
.pet.on::before, .pet.on + .pet::before { display: none; }
.pet.on { background: var(--color-ink); color: var(--color-on-primary); }
.pet:has(.pet-radio:focus-visible) { outline: 2px solid var(--color-ink); outline-offset: 2px; }
.pet-radio { position: absolute; width: 1px; height: 1px; opacity: 0; }
.mark {
  flex: none; width: 20px; height: 20px; margin-left: auto; border-radius: var(--radius-full);
  border: 1.5px solid var(--color-muted); display: grid; place-items: center;
  font-size: 12px; color: var(--color-on-primary);
}
.mark.on { border-color: transparent; background: var(--color-on-primary); color: var(--color-ink); }
.pet.on .pd { color: var(--color-on-primary); opacity: .78; }
.pa { flex: none; }
.pinfo { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.pn { overflow-wrap: anywhere; font-size: var(--font-reading); font-weight: 600; line-height: 1.5; }
.pd { font-size: var(--font-label); color: var(--color-muted); white-space: normal; overflow-wrap: anywhere; line-height: 1.5; }
@media (hover: hover) and (pointer: fine) {
  .pet:not(.on):hover { background: var(--color-surface-soft); }
}
.field-error { margin: 10px 0 0; color: var(--color-danger-text); font-size: var(--font-label); line-height: 1.6; }
</style>
