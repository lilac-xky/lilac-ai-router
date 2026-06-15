import { createRouter, createWebHistory, RouterView } from 'vue-router'
import { h } from 'vue'
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
    {
      path: '/admin',
      name: 'admin',
      component: { render: () => h(RouterView) },
      meta: {
        access: 'admin'
      },
      children: [
        {
          path: 'provider',
          name: '模型提供者管理',
          component: () => import('@/views/admin/ProviderManage.vue'),
        },
        {
          path: 'model',
          name: '模型管理',
          component: () => import('@/views/admin/ModelManage.vue'),
        }
      ]
    }
  ],
})

export default router