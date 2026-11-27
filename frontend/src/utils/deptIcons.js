const IVORY = '#fcf0e4'

// 原因: viewBox 与 stroke-width 已离线按墨迹均值补偿归一，运行时不再测量以避免首帧抖动
export const DEPT_ICONS = [
  {
    key: 'stethoscope',
    label: '内科',
    color: '#3f7fb5',
    viewBox: '-0.109 -2.209 34.719 34.719',
    svg: '<path d="M9 6 L9 11 C9 16 23 16 23 11 L23 6 L19.2 6 L19.2 11 C19.2 13.4 12.8 13.4 12.8 11 L12.8 6 Z" fill="#3f7fb5" stroke="#1d3f5c" stroke-width="1.519" stroke-linejoin="round"/><path d="M14.56 16.94 L21.06 23.44 L23.74 20.76 L17.24 14.26 Z" fill="#3f7fb5" stroke="#1d3f5c" stroke-width="1.519" stroke-linejoin="round"/><rect x="6.6" y="2.2" width="5" height="4.2" rx="2.1" fill="#3f7fb5" stroke="#1d3f5c" stroke-width="1.519"/><rect x="20" y="2.2" width="5" height="4.2" rx="2.1" fill="#3f7fb5" stroke="#1d3f5c" stroke-width="1.519"/><circle cx="24.2" cy="24.4" r="3.7" fill="#3f7fb5" stroke="#1d3f5c" stroke-width="1.519"/><circle cx="24.2" cy="24.4" r="1.9" fill="#b6cfe2" stroke="#1d3f5c" stroke-width="1.063"/><circle cx="13.1" cy="9.2" r="0.85" fill="#fcf0e4"/>'
  },
  {
    key: 'surgery',
    label: '外科',
    color: '#cc6a4f',
    viewBox: '-2.451 -2.351 36.902 36.902',
    svg: '<path d="M6.2 3.6 21.4 18.8 18.6 21.6 3.4 6.4 Z" fill="#cc6a4f" stroke="#71301f" stroke-width="1.614" stroke-linejoin="round"/><path d="M12.8 10.2 15.6 13" fill="none" stroke="#fcf0e4" stroke-width="1.499" stroke-linecap="round"/><path d="M25.8 3.6 10.6 18.8 13.4 21.6 28.6 6.4 Z" fill="#cc6a4f" stroke="#71301f" stroke-width="1.614" stroke-linejoin="round"/><path d="M19.2 10.2 16.4 13" fill="none" stroke="#fcf0e4" stroke-width="1.499" stroke-linecap="round"/><circle cx="8.4" cy="24.4" r="4.2" fill="#f0c4b5" stroke="#71301f" stroke-width="1.614"/><circle cx="21.6" cy="24.4" r="4.2" fill="#f0c4b5" stroke="#71301f" stroke-width="1.614"/><circle cx="8.4" cy="24.4" r="1.6" fill="#cc6a4f"/><circle cx="21.6" cy="24.4" r="1.6" fill="#cc6a4f"/>'
  },
  {
    key: 'skin',
    label: '皮肤科',
    color: '#8e4fae',
    viewBox: '3.067 0.467 25.865 25.865',
    svg: '<path d="M16 3.4c4.4 5.2 7.2 9 7.2 12.8a7.2 7.2 0 0 1-14.4 0c0-3.8 2.8-7.6 7.2-12.8Z" fill="#8e4fae" stroke="#4a2660" stroke-width="1.132" stroke-linejoin="round"/><path d="M16 10.6c1.8 2.2 2.8 3.7 2.8 5.1a2.8 2.8 0 0 1-5.6 0c0-1.4 1-2.9 2.8-5.1Z" fill="#dcc6e8" stroke="#4a2660" stroke-width="0.792"/><path d="M13.6 15.2a2.6 2.6 0 0 0 2.2 2.4" fill="none" stroke="#fcf0e4" stroke-width="0.889" stroke-linecap="round"/>'
  },
  {
    key: 'eye',
    label: '眼科',
    color: '#1a7168',
    viewBox: '0.671 0.671 30.657 30.657',
    svg: '<path d="M2.6 16S7.4 8.2 16 8.2 29.4 16 29.4 16 24.6 23.8 16 23.8 2.6 16 2.6 16Z" fill="#1a7168" stroke="#0a3a35" stroke-width="1.341" stroke-linejoin="round"/><circle cx="16" cy="16" r="5.4" fill="#aed6d1" stroke="#0a3a35" stroke-width="1.207"/><circle cx="16" cy="16" r="2.6" fill="#0a3a35"/><circle cx="14.6" cy="14.6" r="1" fill="#fcf0e4"/>'
  },
  {
    key: 'tooth',
    label: '口腔科',
    color: '#a06a2e',
    viewBox: '0.580 0.180 28.039 28.039',
    svg: '<path d="M16 4.6c-2.5 0-3.6 1.5-5.8 1.5-2.8 0-4.6 2.3-4.6 5.3 0 4 1.7 5.9 2.5 9.4.5 2.1 1.2 3 2.1 3 1.5 0 1.8-2.9 2.6-5.2.4-1 1.1-1.5 1.8-1.5s1.4.5 1.8 1.5c.8 2.3 1.1 5.2 2.6 5.2.9 0 1.6-.9 2.1-3 .8-3.5 2.5-5.4 2.5-9.4 0-3-1.8-5.3-4.6-5.3-2.2 0-3.3-1.5-5.8-1.5Z" fill="#a06a2e" stroke="#513312" stroke-width="1.227" stroke-linejoin="round"/><path d="M11.8 8.2c-1.2.6-1.9 1.8-1.9 3.2 0 1.2.4 2 .8 3" fill="none" stroke="#e2c9a4" stroke-width="1.139" stroke-linecap="round"/><path d="M19.4 9.2c.7.5 1.1 1.4 1.1 2.4" fill="none" stroke="#fcf0e4" stroke-width="0.964" stroke-linecap="round"/>'
  },
  {
    key: 'imaging',
    label: '影像检验',
    color: '#3a4278',
    viewBox: '-2.098 -2.098 36.196 36.196',
    svg: '<path d="M8.4 4.2h-3.2a2 2 0 0 0-2 2v3.2M23.6 4.2h3.2a2 2 0 0 1 2 2v3.2M28.8 22.6v3.2a2 2 0 0 1-2 2h-3.2M8.4 27.8H5.2a2 2 0 0 1-2-2v-3.2" fill="none" stroke="#3a4278" stroke-width="2.488" stroke-linecap="round" stroke-linejoin="round"/><circle cx="16" cy="16" r="7.2" fill="#3a4278" stroke="#1b1f3e" stroke-width="1.742"/><circle cx="16" cy="16" r="3.2" fill="#a8aed0"/><circle cx="16" cy="16" r="1.2" fill="#fcf0e4"/>'
  },
  {
    key: 'vaccine',
    label: '疫苗接种',
    color: '#8b8a1c',
    viewBox: '-4.493 -4.493 38.157 38.157',
    svg: '<g transform="rotate(-45 16 16)"><rect x="10.2" y="5.4" width="11.6" height="16.8" rx="2.6" fill="#8b8a1c" stroke="#45440a" stroke-width="1.669"/><rect x="12.4" y="17.2" width="7.2" height="3.2" rx="1" fill="#dedda0"/><rect x="12.8" y="1.4" width="6.4" height="4.6" rx="1.2" fill="#dedda0" stroke="#45440a" stroke-width="1.502"/><path d="M16 22.2v4.4" fill="none" stroke="#45440a" stroke-width="3.100" stroke-linecap="round"/><path d="M13 8.4h6" fill="none" stroke="#fcf0e4" stroke-width="1.550" stroke-linecap="round"/></g>'
  },
  {
    key: 'nutrition',
    label: '营养行为',
    color: '#3f7d22',
    viewBox: '0.563 -0.837 30.874 30.874',
    svg: '<path d="M4.4 12.2h23.2c0 7-5.2 11.6-11.6 11.6S4.4 19.2 4.4 12.2Z" fill="#3f7d22" stroke="#1e3d0f" stroke-width="1.351" stroke-linejoin="round"/><path d="M4.4 12.2c0 2.4 5.2 4.2 11.6 4.2s11.6-1.8 11.6-4.2" fill="none" stroke="#bcd69f" stroke-width="1.351"/><path d="M4.4 12.2h23.2" fill="none" stroke="#1e3d0f" stroke-width="1.351" stroke-linecap="round"/><path d="M11 8.6c.9-1 .9-2.2 0-3.2M16 8.6c.9-1 .9-2.2 0-3.2M21 8.6c.9-1 .9-2.2 0-3.2" fill="none" stroke="#3f7d22" stroke-width="1.351" stroke-linecap="round"/>'
  },
  {
    key: 'emergency',
    label: '急诊重症',
    color: '#8c2f24',
    viewBox: '2.997 2.297 26.005 26.005',
    svg: '<path d="M12.6 10.6V8.6a2 2 0 0 1 2-2h2.8a2 2 0 0 1 2 2v2" fill="none" stroke="#471310" stroke-width="1.138" stroke-linecap="round"/><rect x="7.6" y="10.8" width="16.8" height="13.2" rx="2.8" fill="#8c2f24" stroke="#471310" stroke-width="1.138"/><rect x="14.2" y="14" width="3.6" height="7" rx="1" fill="#fcf0e4"/><rect x="12.5" y="15.7" width="7" height="3.6" rx="1" fill="#fcf0e4"/>'
  },
  {
    key: 'exotic',
    label: '异宠专科',
    color: '#b0356f',
    viewBox: '0.674 1.007 30.652 30.652',
    svg: '<path d="M11.6 15.6C10 13.6 8.4 9.8 8.4 7.4c0-2.3 2-3.5 3.5-2.2 1.3 1.3 1.7 4.5 1.3 7.3" fill="#e3b3c9" stroke="#5a1738" stroke-width="1.341" stroke-linejoin="round"/><path d="M20.4 15.6c1.6-2 3.2-5.8 3.2-8.2 0-2.3-2-3.5-3.5-2.2-1.3 1.3-1.7 4.5-1.3 7.3" fill="#e3b3c9" stroke="#5a1738" stroke-width="1.341" stroke-linejoin="round"/><path d="M16 28c-5.6 0-9-3.4-9-7.1 0-3.6 3.4-6.1 9-6.1s9 2.5 9 6.1C25 24.6 21.6 28 16 28Z" fill="#b0356f" stroke="#5a1738" stroke-width="1.341" stroke-linejoin="round"/><circle cx="12.6" cy="20.4" r="1.3" fill="#fcf0e4"/><circle cx="19.4" cy="20.4" r="1.3" fill="#fcf0e4"/><path d="M15 23.6h2" fill="none" stroke="#fcf0e4" stroke-width="1.245" stroke-linecap="round"/>'
  }
]

const byKey = new Map(DEPT_ICONS.map((item) => [item.key, item]))
const FALLBACK = byKey.get('stethoscope')

// 原因: 历史数据存 emoji 且可能携带变体选择符 U+FE0F，需先剥离后再映射图标键名
const LEGACY = {
  '🩺': 'stethoscope',
  '🔪': 'surgery',
  '🦴': 'surgery',
  '🩹': 'surgery',
  '🧴': 'skin',
  '👁': 'eye',
  '🦷': 'tooth',
  '🔬': 'imaging',
  '🧬': 'imaging',
  '💉': 'vaccine',
  '🍎': 'nutrition',
  '🚨': 'emergency',
  '❤': 'emergency',
  '⚡': 'emergency',
  '🐰': 'exotic',
  '🐱': 'exotic',
  '🐶': 'exotic',
  '🐾': 'exotic'
}

export function resolveDeptIcon(name) {
  const raw = String(name || '').trim()
  if (byKey.has(raw)) return byKey.get(raw)
  return byKey.get(LEGACY[raw.replace(/\uFE0F/g, '')]) || FALLBACK
}

export const DEPT_ICON_HIGHLIGHT = IVORY
