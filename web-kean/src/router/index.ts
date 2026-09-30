import { createRouter, createWebHistory } from "vue-router";
import { getToken, getUser } from "@/utils/storage";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/login/index.vue"),
      meta: { public: true }
    },
    {
      path: "/",
      component: () => import("@/layouts/AdminLayout.vue"),
      redirect: "/dashboard",
      children: [
        { path: "dashboard", name: "dashboard", component: () => import("@/views/dashboard/index.vue"), meta: { title: "首页概览", en: "Dashboard" } },
        { path: "users", name: "users", component: () => import("@/views/users/index.vue"), meta: { title: "用户管理", en: "User Management" } },
        { path: "tasks", name: "tasks", component: () => import("@/views/tasks/index.vue"), meta: { title: "代课管理", en: "Task Management" } },
        { path: "reports", name: "reports", component: () => import("@/views/reports/index.vue"), meta: { title: "举报审核及反馈", en: "Reports" } },
        { path: "catalog", name: "catalog", component: () => import("@/views/catalog/index.vue"), meta: { title: "学校与校区", en: "School & Campus" } },
        { path: "announcements", name: "announcements", component: () => import("@/views/announcements/index.vue"), meta: { title: "公告与消息", en: "Announcements" } },
        { path: "analytics", name: "analytics", component: () => import("@/views/analytics/index.vue"), meta: { title: "数据统计", en: "Analytics" } },
        { path: "system", name: "system", component: () => import("@/views/system/index.vue"), meta: { title: "系统管理", en: "System Settings" } }
      ]
    }
  ]
});

router.beforeEach((to) => {
  const token = getToken();
  if (!to.meta.public && !token) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (to.path === "/login" && token) {
    const user = getUser();
    if (user?.role === "ADMIN") {
      return { path: "/dashboard" };
    }
  }
  return true;
});

export default router;
