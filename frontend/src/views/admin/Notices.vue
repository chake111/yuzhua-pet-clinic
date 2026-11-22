<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { createNotice, noticeDetail, noticePage, noticeStatus, removeNotice, updateNotice } from '@/api/admin'
import { NOTICE_STATUS, NOTICE_TYPE, options, tagType, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import ImageUpload from '@/components/ImageUpload.vue'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 公告与科普。列表接口不返回正文，编辑前再单独取一次详情，
 * 避免整页数据被长文本撑大。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20, type: null, status: null, keyword: '' })
// 查询时提交筛选快照，翻页和操作后的刷新沿用已提交条件。
let applied = { ...q }

const open = ref(false)
const editing = ref(null)
const busy = ref(false)
const blank = () => ({ title: '', cover: '', summary: '', content: '', type: 1, topFlag: 0, status: 1 })
const form = ref(blank())

async function load() {
  loading.value = true
  try {
    const page = await noticePage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      type: applied.type ?? undefined,
      status: applied.status ?? undefined,
      keyword: applied.keyword.trim() || undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  applied = { ...q }
  q.pageNum = 1
  load()
}

function openCreate() {
  editing.value = null
  form.value = blank()
  open.value = true
}

async function openEdit(row) {
  editing.value = row
  form.value = { ...blank(), ...(await noticeDetail(row.id)) }
  open.value = true
}

async function submit() {
  if (!form.value.title?.trim()) return ElMessage.warning('请填写标题')
  // 草稿允许空正文；只有发布状态才要求正文，与 NoticeService.validate 保持一致
  if (form.value.status === 1 && !form.value.content?.trim()) return ElMessage.warning('请填写正文')
  busy.value = true
  try {
    const payload = { ...form.value, title: form.value.title.trim() }
    delete payload.viewCount
    delete payload.publishTime
    delete payload.publishBy
    delete payload.createTime
    delete payload.updateTime
    if (editing.value) await updateNotice(editing.value.id, payload)
    else await createNotice(payload)
    ElMessage.success('已保存')
    open.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function setStatus(row, status) {
  await noticeStatus(row.id, status)
  ElMessage.success(`已置为「${text(NOTICE_STATUS, status)}」`)
  load()
}

async function toggleTop(row) {
  // 保存接口是整体覆盖，列表行不一定带正文，先取一次完整详情
  const full = await noticeDetail(row.id)
  await updateNotice(row.id, { ...full, topFlag: row.topFlag === 1 ? 0 : 1 })
  ElMessage.success(row.topFlag === 1 ? '已取消置顶' : '已置顶')
  load()
}

async function del(row) {
  const ok = await ElMessageBox.confirm(`删除「${row.title}」？`, '删除公告',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }).then(() => true).catch(() => false)
  if (!ok) return
  await removeNotice(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-input v-model="q.keyword" placeholder="搜标题 / 摘要" clearable class="w200" :prefix-icon="Search"
                @keyup.enter="search" />
      <el-select v-model="q.type" placeholder="全部类型" clearable class="w130">
        <el-option v-for="o in options(NOTICE_TYPE)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w130">
        <el-option v-for="o in options(NOTICE_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-button :loading="loading" @click="search">查询</el-button>
      <div class="sp" />
      <el-button type="primary" @click="openCreate">新建内容</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>公告科普</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="还没有内容">
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <em v-if="row.topFlag === 1" class="top">置顶</em>
            <b>{{ row.title }}</b>
            <div class="pc-muted sm">{{ row.summary }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="106">
          <template #default="{ row }">
            <StatusChip tone="plain">{{ text(NOTICE_TYPE, row.type) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="阅读" width="80" prop="viewCount" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <StatusChip :tone="tagType(NOTICE_STATUS, row.status)">{{ text(NOTICE_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="发布/更新" width="112">
          <template #default="{ row }">{{ toDate(row.publishTime || row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status !== 1" link type="success" size="small" @click="setStatus(row, 1)">发布</el-button>
            <el-button v-if="row.status === 1" link type="warning" size="small" @click="setStatus(row, 2)">下架</el-button>
            <el-button v-if="row.status === 1" link size="small" @click="setStatus(row, 0)">转草稿</el-button>
            <el-button link size="small" @click="toggleTop(row)">{{ row.topFlag === 1 ? '取消置顶' : '置顶' }}</el-button>
            <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-dialog v-model="open" :title="editing ? '编辑内容' : '新建内容'" width="720px" top="5vh">
      <el-form :model="form" label-width="72px">
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="60" show-word-limit /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="类型">
              <el-select v-model="form.type" class="full">
                <el-option v-for="o in options(NOTICE_TYPE)" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-select v-model="form.status" class="full">
                <el-option v-for="o in options(NOTICE_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="置顶"><el-switch v-model="form.topFlag" :active-value="1" :inactive-value="0" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="封面">
          <ImageUpload v-model="form.cover" :max="1" kind="admin" />
        </el-form-item>
        <el-form-item label="摘要"><el-input v-model="form.summary" type="textarea" :rows="2" maxlength="120" show-word-limit /></el-form-item>
        <el-form-item label="正文" :required="form.status === 1">
          <el-input v-model="form.content" type="textarea" :rows="10" maxlength="4000" show-word-limit
                    placeholder="请输入正文" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w130 { width: 130px; }
.w200 { width: 200px; }
.top { font-style: normal; font-size: 11px; color: #fff; background: var(--color-warning); border-radius: 4px; padding: 1px 5px; margin-right: 6px; }
.sm { font-size: 12px; margin-top: 3px; }
.full { width: 100%; }
</style>
