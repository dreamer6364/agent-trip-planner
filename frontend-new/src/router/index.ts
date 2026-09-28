import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      { path: '', name: 'landing', component: () => import('@/views/LandingPage.vue') },
      { path: 'explore', name: 'explore', component: () => import('@/views/ExplorePage.vue') },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardPage.vue'), meta: { requiresAuth: true } },
      { path: 'trips/create', name: 'trip-create', component: () => import('@/views/TripCreatePage.vue'), meta: { requiresAuth: true } },
      { path: 'trips/:id', name: 'trip-detail', component: () => import('@/views/TripDetailPage.vue'), meta: { requiresAuth: true } },
      { path: 'trips/:id/planning', name: 'planning', component: () => import('@/views/PlanningPage.vue'), meta: { requiresAuth: true } },
      { path: 'profile', name: 'profile', component: () => import('@/views/ProfilePage.vue'), meta: { requiresAuth: true } },
    ],
  },
  {
    path: '/',
    component: () => import('@/layouts/AuthLayout.vue'),
    children: [
      { path: 'login', name: 'login', component: () => import('@/views/AuthPage.vue'), meta: { guest: true } },
      { path: 'register', name: 'register', component: () => import('@/views/AuthPage.vue'), meta: { guest: true } },
    ],
  },
  {
    path: '/s/:token',
    name: 'share',
    component: () => import('@/views/SharePage.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 判断 token 是否仍然有效（解析 JWT exp，非 JWT 时视为有效）
 * 过期 token 会导致「已登录」假象：访问 /login、/register 被守卫重定向到 /dashboard，
 * 用户看起来就是「注册页打不开 / 无法注册」
 */
function isTokenAlive(token: string | null): boolean {
  if (!token) return false
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return true
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')))
    if (typeof payload.exp !== 'number') return true
    return payload.exp * 1000 > Date.now() + 5000
  } catch {
    return true
  }
}

router.beforeEach((to) => {
  const token = localStorage.getItem('tf_token')
  const alive = isTokenAlive(token)
  if (token && !alive) {
    localStorage.removeItem('tf_token')
    localStorage.removeItem('tf_refresh')
  }
  if (to.meta.requiresAuth && !alive) {
    return { name: 'login' }
  }
  if (to.meta.guest && alive) {
    return { name: 'dashboard' }
  }
})

export default router
