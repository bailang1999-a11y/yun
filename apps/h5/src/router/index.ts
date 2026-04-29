import { createRouter, createWebHistory } from 'vue-router'

const HomeView = () => import('../views/HomeView.vue')
const GoodsDetailView = () => import('../views/GoodsDetailView.vue')
const CheckoutView = () => import('../views/CheckoutView.vue')
const PaymentResultView = () => import('../views/PaymentResultView.vue')
const OrdersView = () => import('../views/OrdersView.vue')
const CardsView = () => import('../views/CardsView.vue')
const MineView = () => import('../views/MineView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/goods/:id', name: 'goods-detail', component: GoodsDetailView },
    { path: '/checkout/:orderNo', name: 'checkout', component: CheckoutView },
    { path: '/result/:orderNo', name: 'payment-result', component: PaymentResultView },
    { path: '/orders', name: 'orders', component: OrdersView },
    { path: '/cards', name: 'cards', component: CardsView },
    { path: '/mine', name: 'mine', component: MineView }
  ]
})

export default router
