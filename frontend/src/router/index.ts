import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/views/Home.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: 'userLogin',
      component: () => import('@/views/user/UserLogin.vue'),
    },
    {
      path: '/user/register',
      name: 'userRegister',
      component: () => import('@/views/user/UserRegister.vue'),
    },
    {
      path: '/user/apiKey',
      name: 'apiKey',
      component: () => import('@/views/user/ApiKey.vue'),
    },
    {
      path: '/user/chat',
      name: 'chat',
      component: () => import('@/views/Chat.vue'),
    },
  ],
})

export default router