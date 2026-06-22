import { createRouter, createWebHistory, RouterView } from 'vue-router'
import { h } from 'vue'
import Profile from '@/views/user/Profile.vue'
import CallHistory from '@/views/user/CallHistory.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: '主页',
      component: Profile,
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
      path: '/user/profile',
      name: '个人中心',
      component: Profile,
    },
    {
      path: '/user/history',
      name: '调用历史',
      component: CallHistory,
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
        },
        {
          path: 'blackList',
          name: '黑名单管理',
          component: () => import('@/views/admin/BlackManage.vue'),
        },
        {
          path: 'user',
          name: '用户管理',
          component: () => import('@/views/admin/UserManager.vue'),
        }
      ]
    }
  ],
})

export default router