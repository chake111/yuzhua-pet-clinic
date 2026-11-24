import { reactive } from 'vue'

const viewer = reactive({
  open: false,
  urls: [],
  captions: [],
  title: '',
  owner: null,
  index: 0
})

const toList = (v) =>
  (Array.isArray(v) ? v : String(v || '').split(','))
    .map((s) => String(s || '').trim())
    .filter(Boolean)

export function openImageViewer(urls, index = 0, { captions = [], title = '', owner = null } = {}) {
  const list = toList(urls)
  if (!list.length) return
  viewer.urls = list
  viewer.captions = captions.length === list.length ? captions.map((c) => c || '') : list.map(() => '')
  viewer.title = title
  viewer.owner = owner
  viewer.index = Math.min(Math.max(Number(index) || 0, 0), list.length - 1)
  viewer.open = true
}

export function closeImageViewer() {
  viewer.open = false
  viewer.urls = []
  viewer.captions = []
  viewer.title = ''
  viewer.owner = null
  viewer.index = 0
}

export function closeImageViewerFor(owner) {
  if (viewer.owner === owner) closeImageViewer()
}

// 原因: el-image-viewer 仅在挂载时读取 initial-index，此处回写索引不会反向触发重渲染
export function onViewerSwitch(index) {
  viewer.index = Number(index) || 0
}

export const imageViewer = viewer
