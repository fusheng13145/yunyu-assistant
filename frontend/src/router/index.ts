import { createRouter, createWebHistory } from 'vue-router'
import SmartRobot from '../views/SmartRobot.vue'
import Login from '../views/Login.vue'
import Register from '../views/Register.vue'

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
      component: Login,
    },
    {
      path: '/register',
      name: 'Register',
      component: Register,
    },
    {
      path: '/smartrobot',
      name: 'SmartRobot',
      component: SmartRobot,
      meta: { requiresAuth: true },
    },
    {
      path: '/chat-robot/:assistantId?',
      redirect: () => ({ name: 'SmartRobot' }),
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')

  if (to.meta.requiresAuth && !token) {
    next({ name: 'Login' })
  } else if ((to.name === 'Login' || to.name === 'Register') && token) {
    next({ name: 'SmartRobot' })
  } else {
    next()
  }
})

export default router
