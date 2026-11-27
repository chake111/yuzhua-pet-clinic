import { createRouter, createWebHistory } from 'vue-router'
import ClientLayout from '@/layouts/ClientLayout.vue'
import Home from '@/views/client/Home.vue'
import ClientLogin from '@/views/client/Login.vue'
import AdminLogin from '@/views/admin/Login.vue'
import { useAdminStore } from '@/stores/admin'
import { useUserStore } from '@/stores/client'

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
      // 原因: 接诊工作台整页为临床动作，仅对医生开放入口；实际鉴权仍以后端为准
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
      // 原因: 角色越界访问管理或临床页面时退回各自工作台首页，避免空白页
      return { path: '/admin/dashboard' }
    }
    return true
  }
  if (to.meta.auth && !useUserStore().logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

// 原因: Element Plus 弹窗挂载在 body 上且登录/注册不在布局内，需挂 body 级作用域类覆盖全局样式
router.afterEach((to) => {
  document.body.classList.toggle('client-mode', !to.path.startsWith('/admin'))
  document.body.classList.toggle('admin-mode', to.path.startsWith('/admin'))
})

export default router
