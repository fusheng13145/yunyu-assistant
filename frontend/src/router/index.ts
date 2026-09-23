import { createRouter, createWebHistory } from 'vue-router'
import { getToken, isTokenExpired, isTokenFresh, renewToken } from '../api/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/login',
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue'),
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('../views/Register.vue'),
    },
    {
      path: '/smartrobot',
      name: 'SmartRobot',
      component: () => import('../views/SmartRobot.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/chatrobot',
      name: 'ChatRobot',
      component: () => import('../views/ChatRobot.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/records',
      name: 'CallRecords',
      component: () => import('../views/CallRecords.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/org',
      name: 'Org',
      component: () => import('../views/Org.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/billing',
      name: 'Billing',
      component: () => import('../views/Billing.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/apps',
      name: 'Apps',
      component: () => import('../views/Apps.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      name: 'Admin',
      component: () => import('../views/Admin.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('../views/NotFound.vue'),
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  // 访问令牌已过期或临期时先换发：否则进页面后所有请求集体 401，用户看到"莫名报错"
  if (to.meta.requiresAuth && getToken() && !isTokenFresh(getToken())) {
    await renewToken()
  }
  // 换发失败（refresh 令牌也过期）等同未登录，不能放行受保护路由
  const token = isTokenExpired(getToken()) ? null : getToken()
  const role = localStorage.getItem('role') || 'user'

  if (to.meta.requiresAuth && !token) {
    next({ name: 'Login' })
  } else if (to.meta.requiresAdmin && role !== 'admin') {
    // 非管理员访问管理后台 → 回到助手主页
    next({ name: 'SmartRobot' })
  } else if ((to.name === 'Login' || to.name === 'Register') && token) {
    next({ name: 'SmartRobot' })
  } else {
    next()
  }
})

export default router
