import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../components/AdminLayout.vue'

const LoginView = () => import('../views/LoginView.vue')
const DashboardView = () => import('../views/DashboardView.vue')
const GoodsView = () => import('../views/GoodsView.vue')
const PriceTemplatesView = () => import('../views/PriceTemplatesView.vue')
const CategoriesView = () => import('../views/CategoriesView.vue')
const SuppliersView = () => import('../views/SuppliersView.vue')
const UpstreamMonitorView = () => import('../views/UpstreamMonitorView.vue')
const OrdersView = () => import('../views/OrdersView.vue')
const OrderDetailView = () => import('../views/OrderDetailView.vue')
const UsersView = () => import('../views/UsersView.vue')
const SettingsView = () => import('../views/SettingsView.vue')
const AuditView = () => import('../views/AuditView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { title: '后台登录', public: true } },
    {
      path: '/',
      component: AdminLayout,
      children: [
        { path: '', name: 'dashboard', component: DashboardView, meta: { title: '实时业务看板' } },
        { path: 'goods', name: 'goods', component: GoodsView, meta: { title: '商品管理' } },
        { path: 'goods/price-templates', name: 'goods-price-templates', component: PriceTemplatesView, meta: { title: '价格模板配置' } },
        { path: 'categories', name: 'categories', component: CategoriesView, meta: { title: '分类管理' } },
        { path: 'suppliers', name: 'suppliers', component: SuppliersView, meta: { title: '供应商管理' } },
        { path: 'upstream-monitor', name: 'upstream-monitor', component: UpstreamMonitorView, meta: { title: '上游监控看板' } },
        { path: 'users', name: 'users', component: UsersView, meta: { title: '用户与权限' } },
        { path: 'settings', name: 'settings', component: SettingsView, meta: { title: '系统设置' } },
        { path: 'audit', name: 'audit', component: AuditView, meta: { title: '审计与开放平台' } },
        { path: 'orders', name: 'orders', component: OrdersView, meta: { title: '订单管理' } },
        { path: 'orders/:orderNo', name: 'order-detail', component: OrderDetailView, meta: { title: '订单详情' } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!localStorage.getItem('xiyiyun_admin_token')) {
    return { name: 'login' }
  }
  return true
})

export default router
