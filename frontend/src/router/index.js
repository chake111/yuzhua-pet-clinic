import { createRouter, createWebHistory } from 'vue-router'
import ClientLayout from '@/layouts/ClientLayout.vue'
import Home from '@/views/client/Home.vue'
import ClientLogin from '@/views/client/Login.vue'
import AdminLogin from '@/views/admin/Login.vue'
import { useAdminStore } from '@/stores/admin'
import { useUserStore } from '@/stores/client'

/**
 * 路由表。一个工程里放两套界面：/ 是宠物主人端，/admin 是医院后台。
 *
 * meta.auth  → 需要用户端登录
 * meta.admin → 需要后台登录；meta.roles 再细分管理员/医生
 * 未登录可浏览首页、医生列表与详情，这是需求里明确要的（G3）。
 * 首屏高频直达的 /、/login、/admin/login 均已去 Element Plus 化并直入入口包，消除二次 chunk 瀑布。
 */
const routes = [
  { path: '/login', name: 'login', component: ClientLogin },
  { path: '/register', name: 'register', component: () => import('@/views/client/Register.vue') },
  {
    path: '/',
    component: ClientLayout,
    children: [
      { path: '', name: 'home', component: Home },
      { path: 'doctors', name: 'doctors', component: () => import('@/views/client/DoctorList.vue') },
      { path: 'doctor/:id', name: 'doctor', component: () => import('@/views/client/DoctorDetail.vue') },
      { path: 'book/:doctorId', name: 'book', component: () => import('@/views/client/Booking.vue'), meta: { auth: true } },
      { path: 'notices', name: 'notices', component: () => import('@/views/client/NoticeList.vue') },
      { path: 'notice/:id', name: 'notice', component: () => import('@/views/client/NoticeDetail.vue') },
      { path: 'order/:id', name: 'order', component: () => import('@/views/client/OrderDetail.vue'), meta: { auth: true } },
      { path: 'consult', name: 'consult', component: () => import('@/views/client/ConsultList.vue'), meta: { auth: true } },
      { path: 'consult/new/:doctorId', name: 'consultNew', component: () => import('@/views/client/NewConsult.vue'), meta: { auth: true } },
      { path: 'consult/:id', name: 'consultRoom', component: () => import('@/views/client/ConsultRoom.vue'), meta: { auth: true } },
      { path: 'record/:id', name: 'record', component: () => import('@/views/client/RecordDetail.vue'), meta: { auth: true } },
      { path: 'review/:orderId', name: 'review', component: () => import('@/views/client/ReviewForm.vue'), meta: { auth: true } },
      {
        path: 'me',
        component: () => import('@/views/client/Mine.vue'),
        meta: { auth: true },
        children: [
          { path: '', redirect: '/me/orders' },
          { path: 'orders', name: 'my-orders', component: () => import('@/views/client/MyOrders.vue') },
          { path: 'pets', name: 'my-pets', component: () => import('@/views/client/MyPets.vue') },
          { path: 'records', name: 'my-records', component: () => import('@/views/client/MyRecords.vue') },
          { path: 'profile', name: 'my-profile', component: () => import('@/views/client/MyProfile.vue') }
        ]
      }
    ]
  },

  { path: '/admin/login', name: 'admin-login', component: AdminLogin },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { admin: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/admin/Dashboard.vue') },
      { path: 'appointments', name: 'admin-appointments', component: () => import('@/views/admin/Appointments.vue') },
      { path: 'appointment/:id', name: 'admin-appointment', component: () => import('@/views/admin/AppointmentDetail.vue') },
      { path: 'schedule', name: 'admin-schedule', component: () => import('@/views/admin/Schedules.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'my-schedule', name: 'admin-my-schedule', component: () => import('@/views/admin/MySchedule.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'doctors', name: 'admin-doctors', component: () => import('@/views/admin/Doctors.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'departments', name: 'admin-departments', component: () => import('@/views/admin/Departments.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'users', name: 'admin-users', component: () => import('@/views/admin/Users.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'pets', name: 'admin-pets', component: () => import('@/views/admin/Pets.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'records', name: 'admin-records', component: () => import('@/views/admin/Records.vue') },
      { path: 'record/:id', name: 'admin-record-detail', component: () => import('@/views/admin/RecordDetail.vue') },
      // 接诊工作台整页都是临床动作（接诊/结束/写病历），按 R4 只开放给医生；
      // 管理员从工单列表进只读详情，不再落到这一页。真正的拦截在后端，这里只是不把入口摆出来。
      { path: 'workbench/:orderId', name: 'admin-workbench', component: () => import('@/views/admin/Workbench.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'reviews', name: 'admin-reviews', component: () => import('@/views/admin/Reviews.vue') },
      { path: 'notices', name: 'admin-notices', component: () => import('@/views/admin/Notices.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'consult-records', name: 'admin-consult-records', component: () => import('@/views/admin/ConsultRecords.vue'), meta: { roles: ['ADMIN'] } },
      { path: 'consult', name: 'admin-consult', component: () => import('@/views/admin/ConsultList.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'consult/:id', name: 'admin-consult-room', component: () => import('@/views/admin/ConsultRoom.vue'), meta: { roles: ['DOCTOR'] } },
      { path: 'profile', name: 'admin-profile', component: () => import('@/views/admin/Profile.vue') }
    ]
  },

  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFound.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (to, from, saved) => saved || { top: 0 }
})

router.beforeEach((to) => {
  if (to.meta.admin) {
    const store = useAdminStore()
    if (!store.logged) return { path: '/admin/login', query: { redirect: to.fullPath } }
    if (to.meta.roles && !to.meta.roles.includes(store.role)) {
      // 医生误入管理项、管理员误入接诊会话，都退回各自的首页而不是给一个空白页
      return { path: '/admin/dashboard' }
    }
    return true
  }
  if (to.meta.auth && !useUserStore().logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

/**
 * 用户端作用域类挂在 body 上：Element 的弹窗 teleport 到 body、Login/Register 又不在
 * ClientLayout 内，只有 body 级选择器（styles/main.css 的 body.client-mode）能覆盖全。
 * 管理端挂 admin-mode，限定后台与弹窗的样式范围。
 */
router.afterEach((to) => {
  document.body.classList.toggle('client-mode', !to.path.startsWith('/admin'))
  document.body.classList.toggle('admin-mode', to.path.startsWith('/admin'))
})

export default router
