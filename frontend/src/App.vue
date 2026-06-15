<script setup lang="ts">
import { onMounted } from 'vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'

const loginUserStore = useLoginUserStore()

// 应用启动/刷新时，根据后端会话恢复登录态
onMounted(() => {
  loginUserStore.fetchLoginUser()
})
</script>

<template>
  <BasicLayout />
</template>