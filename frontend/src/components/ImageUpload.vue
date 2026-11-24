<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Loading, PictureFilled, Plus } from '@element-plus/icons-vue'
import { upload } from '@/api/http'
import { openImageViewer } from '@/utils/imageViewer'

const props = defineProps({
  modelValue: { type: String, default: '' },
  max: { type: Number, default: 6 },
  label: { type: String, default: '已上传的图片' },
  // 原因: /common/upload 不在 /admin 前缀下且同时接受双端 token，后台必须显式传入 'admin'
  kind: { type: String, default: 'client' }
})
const emit = defineEmits(['update:modelValue'])
const busy = ref(false)
const input = ref(null)

const urls = computed(() => (props.modelValue || '').split(',').map((s) => s.trim()).filter(Boolean))

function write(list) {
  emit('update:modelValue', list.join(','))
}

async function change(e) {
  const files = [...e.target.files]
  e.target.value = ''
  if (!files.length) return
  const room = props.max - urls.value.length
  if (room <= 0) return ElMessage.warning(`最多上传 ${props.max} 张`)
  busy.value = true
  try {
    for (const file of files.slice(0, room)) {
      const { url } = await upload(file, props.kind)
      write([...urls.value, url])
    }
  } catch {
    // 原因: 请求层已提示失败原因，此处仅中止后续上传并保留已写入表单的图片
  } finally {
    busy.value = false
  }
}

const preview = (url) => openImageViewer(urls.value, urls.value.indexOf(url), { title: props.label })

const remove = (url) => write(urls.value.filter((u) => u !== url))
</script>

<template>
  <div class="img-up">
    <div v-for="u in urls" :key="u" class="thumb">
      <el-image :src="u" fit="cover" class="pic" alt="已上传的照片" @click="preview(u)">
        <template #error>
          <div class="bad"><el-icon :size="16"><PictureFilled /></el-icon></div>
        </template>
      </el-image>
      <button class="del" type="button" aria-label="移除这张照片" @click="remove(u)">
        <el-icon :size="12"><Close /></el-icon>
      </button>
    </div>
    <button v-if="urls.length < max" class="add" type="button" :aria-label="`上传照片，已选 ${urls.length} 张，最多 ${max} 张`" :disabled="busy" @click="input.click()">
      <el-icon v-if="busy" class="is-loading"><Loading /></el-icon>
      <el-icon v-else :size="20"><Plus /></el-icon>
      <span>{{ urls.length }}/{{ max }}</span>
    </button>
    <input ref="input" type="file" accept="image/*" multiple hidden @change="change" />
  </div>
</template>

<style scoped>
.img-up { display: flex; flex-wrap: wrap; gap: 8px; }
.thumb, .add { width: 84px; height: 84px; border-radius: var(--radius-sm); position: relative; }
.pic { width: 100%; height: 100%; border-radius: var(--radius-sm); display: block; cursor: zoom-in; }
.bad {
  width: 100%; height: 100%; border-radius: var(--radius-sm); display: grid; place-items: center;
  background: var(--color-surface-soft); color: var(--color-muted);
}
/* 28px 是本组件的通用尺寸，两端一致。此前 main.css 用 body.client-mode .img-up .del
   把用户端顶到 28px、后台留在 20px，同一个组件两种外观；尺寸本来就该归组件所有。 */
.del {
  position: absolute; top: 0; right: 0; width: 28px; height: 28px; padding: 0;
  border: none; border-radius: 50%; background: var(--color-ink); color: #fff;
  cursor: pointer; display: grid; place-items: center;
}
.add {
  border: 1px dashed var(--color-border-strong); background: var(--color-surface-soft);
  color: var(--color-muted); cursor: pointer; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 2px; font-size: 13px;
  transition: border-color .18s var(--ease), color .18s var(--ease);
}
@media (hover: hover) and (pointer: fine) {
  .add:hover { border-color: var(--color-ink); color: var(--color-ink); }
}
</style>
