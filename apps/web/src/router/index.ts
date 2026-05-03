import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { useSessionStore } from '../stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/goods/:id', name: 'goods-detail', component: () => import('../views/GoodsDetailView.vue') },
    { path: '/orders', name: 'orders', component: () => import('../views/OrdersView.vue'), meta: { requiresAuth: true } },
    {
      path: '/orders/:orderNo',
      name: 'order-detail',
      component: () => import('../views/OrderDetailView.vue'),
      meta: { requiresAuth: true }
    },
    { path: '/account', name: 'account', component: () => import('../views/AccountView.vue'), meta: { requiresAuth: true } },
    {
      path: '/account/profile',
      name: 'profile',
      component: () => import('../views/ProfileView.vue'),
      meta: { requiresAuth: true }
    },
    { path: '/account/api', name: 'api', component: () => import('../views/ApiView.vue'), meta: { requiresAuth: true } },
    {
      path: '/account/recharge',
      name: 'recharge',
      component: () => import('../views/RechargeView.vue'),
      meta: { requiresAuth: true }
    },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  session.restore()
  if (session.token && !session.profile && !session.profileLoading) {
    await session.ensureProfile()
  }
  if (to.meta.requiresAuth && !session.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && session.isLoggedIn) {
    return { name: 'home' }
  }
  return true
})

export default router
