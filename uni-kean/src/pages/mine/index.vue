<script setup lang="ts">
import { fetchMe } from "@/api/auth";
import { listMyApplied, listMyPublished } from "@/api/task";
import FallbackImage from "@/components/FallbackImage.vue";
import OngoingTasks from "@/components/OngoingTasks.vue";
import PageBackdrop from "@/components/PageBackdrop.vue";
import PersonAvatar from "@/components/PersonAvatar.vue";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useMineCover } from "@/composables/useMineCover";
import { useOngoingTasks } from "@/composables/useOngoingTasks";
import { usePageWallpaper } from "@/composables/usePageWallpaper";
import { useUserStore } from "@/store/user";
import { formatRoleRating, genderLabel, restrictionLabels } from "@/utils/format";
import { refreshMessageBadge } from "@/utils/messageBadge";
import { resolveMediaUrl } from "@/utils/request";
import { onShow } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

const userStore = useUserStore();
const { wallpaperOn, wallpaperImage } = usePageWallpaper();
const { heroSrc, isCustom, uploading, uploadLabel, openCoverSheet } = useMineCover();
const { items: ongoing, error: ongoingError, load: loadOngoing } = useOngoingTasks();
const isLoggedIn = userStore.isLoggedIn;
const user = computed(() => userStore.state.user);
const statusBarHeight = uni.getSystemInfoSync().statusBarHeight || 20;
// 统计数字用 null 表示「还不知道」：请求失败时保留上一次的值，
// 归零会让用户以为自己的数据被清空了。
const publishedCount = ref<number | null>(null);
const appliedCount = ref<number | null>(null);

const avatarText = computed(() => {
  const name = user.value?.nickname || user.value?.username || "我";
  return name.slice(0, 1);
});

const avatarSrc = computed(() => resolveMediaUrl(user.value?.avatarUrl));

const schoolLine = computed(() => {
  const school = user.value?.schoolName || "未设置学校";
  const campus = user.value?.campusName;
  return campus ? `${school} · ${campus}` : school;
});

const genderText = computed(() => genderLabel(user.value?.gender));

const ratingText = computed(() => {
  const publish = formatRoleRating(user.value?.publishRatingAvg, user.value?.publishRatingCount);
  const apply = formatRoleRating(user.value?.applyRatingAvg, user.value?.applyRatingCount);
  return { publish, apply };
});

const limitTags = computed(() => restrictionLabels(user.value));

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login" });
}

function go(url: string) {
  uni.navigateTo({ url });
}

async function refreshUser() {
  if (!isLoggedIn.value) {
    publishedCount.value = null;
    appliedCount.value = null;
    return;
  }
  try {
    const latest = await fetchMe();
    if (userStore.state.token) {
      userStore.setLogin(userStore.state.token, latest);
    }
  } catch {
    // 保持本地缓存
  }
  try {
    const [published, applied] = await Promise.all([listMyPublished(1, 1, true), listMyApplied(1, 1, true)]);
    publishedCount.value = published.total || 0;
    appliedCount.value = applied.total || 0;
  } catch {
    // 请求失败时保留上一次的统计值，不归零：全变成 0 会被理解成数据被清空。
  }
}

onShow(() => {
  refreshUser();
  loadOngoing();
  refreshMessageBadge();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    loadOngoing();
  }
});
</script>

<template>
  <view class="page" :class="{ skinned: wallpaperOn }">
    <PageBackdrop :src="wallpaperImage" />
    <!-- 未登录兜底（方案 A）：加了全站登录门禁后 guest 态基本不可达（只有 token 恰好失效的
         那一帧会渲染），所以这里**不再做**「大头像 + 未登录大标题 + 登录按钮」的 hero 区，
         只留一行灰字。⚠️ 之所以还留一行可点的字而不是纯空白：冷启动直接打开本页时，
         getCurrentRoute() 有一瞬可能还取不到当前页（守卫会 no-op 等下一次），
         那种情况下这行字是用户唯一的手动出口；行为仍是原有的 goLogin()，未改跳转方式。 -->
    <view v-if="!isLoggedIn" class="guest">
      <view class="status-bar" :style="{ height: statusBarHeight + 'px' }" />
      <view class="nav-title">我的</view>
      <view class="guest-hint" @click="goLogin">正在跳转登录…如未跳转，点此登录</view>
    </view>

    <view v-else class="logged">
      <view class="hero" :class="{ custom: isCustom }" @longpress="openCoverSheet">
        <FallbackImage class="hero-bg" :src="heroSrc" mode="aspectFill" />
        <view class="hero-wash" />
        <view class="status-bar" :style="{ height: statusBarHeight + 'px' }" />
        <view class="nav-title">我的</view>
        <view class="cover-btn" :style="{ top: statusBarHeight + 8 + 'px' }" @click.stop="openCoverSheet">{{ uploading ? uploadLabel || "上传中" : "更换背景" }}</view>
        <view class="profile-row">
          <FallbackImage v-if="avatarSrc" class="avatar" :src="avatarSrc" mode="aspectFill" />
          <view v-else class="avatar avatar-text">{{ avatarText }}</view>
          <view class="profile-main">
            <view class="name-line">
              <text class="nickname">{{ user?.nickname || user?.username }}</text>
              <text class="gender-tag">{{ genderText }}</text>
            </view>
            <view class="school-line">{{ schoolLine }}</view>
            <view class="rate-line">
              <text class="stars">发布 {{ ratingText.publish }}</text>
              <text class="done">完成 {{ user?.publishCompletedCount || 0 }} 次</text>
            </view>
            <view class="rate-line">
              <text class="stars">代课 {{ ratingText.apply }}</text>
              <text class="done">完成 {{ user?.completedCount || 0 }} 次</text>
            </view>
            <view v-if="limitTags.length" class="limit-line">{{ limitTags.join(" · ") }}</view>
            <view class="cred-line">取消 {{ user?.cancelledCount || 0 }} · 被举报 {{ user?.reportedCount || 0 }}</view>
          </view>
        </view>
      </view>
      <view class="stats">
        <view class="stat">
          <view class="num">{{ publishedCount === null ? "—" : publishedCount }}</view>
          <view class="label">我发布</view>
        </view>
        <view class="stat">
          <view class="num">{{ appliedCount === null ? "—" : appliedCount }}</view>
          <view class="label">我申请</view>
        </view>
        <view class="stat">
          <view class="num">{{ user?.completedCount || 0 }}</view>
          <view class="label">代课完成</view>
        </view>
      </view>
      <view class="ongoing-wrap">
        <OngoingTasks :items="ongoing" :limit="5" />
        <!-- 仅失败时多一行小字：没有失败时不渲染，观感与原来完全一致 -->
        <view v-if="ongoingError" class="ongoing-error" @click="loadOngoing">进行中的代课加载失败，点击重试</view>
      </view>
      <view class="section">
        <view class="section-title">我的代课</view>
        <wd-cell-group border>
          <wd-cell title="进行中的代课" is-link @click="go('/pages/mine/ongoing')">
            <template #icon><text class="cell-icon">⏱</text></template>
          </wd-cell>
          <wd-cell title="我发布的" is-link @click="go('/pages/mine/published')">
            <template #icon><text class="cell-icon">📄</text></template>
          </wd-cell>
          <wd-cell title="我申请的" is-link @click="go('/pages/mine/applied')">
            <template #icon><text class="cell-icon">📝</text></template>
          </wd-cell>
          <wd-cell title="我的收藏" is-link @click="go('/pages/mine/favorites')">
            <template #icon><text class="cell-icon">⭐</text></template>
          </wd-cell>
        </wd-cell-group>
      </view>
      <view class="section">
        <view class="section-title">其他</view>
        <wd-cell-group border>
          <wd-cell title="个人资料" is-link @click="go('/pages/mine/profile')">
            <template #icon>
              <view class="cell-icon">
                <PersonAvatar :size="20" />
              </view>
            </template>
          </wd-cell>
          <wd-cell title="我的评价" is-link @click="go('/pages/mine/reviews')">
            <template #icon><text class="cell-icon">💬</text></template>
          </wd-cell>
          <wd-cell title="黑名单" is-link @click="go('/pages/mine/blacklist')">
            <template #icon><text class="cell-icon">🚫</text></template>
          </wd-cell>
          <wd-cell title="举报与反馈" is-link @click="go('/pages/mine/report-hub')">
            <template #icon><text class="cell-icon">⚑</text></template>
          </wd-cell>
          <wd-cell title="设置" is-link @click="go('/pages/mine/settings')">
            <template #icon><text class="cell-icon">⚙</text></template>
          </wd-cell>
        </wd-cell-group>
      </view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  background: #f4f6fb;
}
.page.skinned {
  background: transparent;
}
.guest {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  background: linear-gradient(180deg, #edf3ff 0%, #f7f9fc 42%, #f7f9fc 100%);
}
.logged {
  position: relative;
  z-index: 1;
}
.status-bar {
  width: 100%;
}
.nav-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  color: var(--kean-text);
  padding: 10px 0 8px;
}
.hero {
  position: relative;
  overflow: hidden;
  background: linear-gradient(180deg, #6eb4f4 0%, #c5e4fb 70%, #f4f7fb 100%);
  padding-bottom: 48px;
}
.hero-bg {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}
.hero-wash {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  z-index: 1;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08) 0%, rgba(244, 247, 251, 0.18) 100%);
}
.hero.custom .hero-wash {
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.28) 0%, rgba(15, 23, 42, 0.08) 100%);
}
.hero > .status-bar,
.hero > .nav-title,
.hero > .profile-row {
  position: relative;
  z-index: 2;
}
.cover-btn {
  position: absolute;
  right: 16px;
  top: 30px;
  z-index: 3;
  padding: 4px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--kean-primary-active);
  font-size: 12px;
}
.hero.custom .cover-btn {
  background: rgba(15, 23, 42, 0.28);
  color: var(--kean-card);
}
.hero.custom .nav-title,
.hero.custom .nickname,
.hero.custom .school-line,
.hero.custom .rate-line,
.hero.custom .cred-line {
  color: var(--kean-card);
}
.hero.custom .gender-tag {
  background: rgba(255, 255, 255, 0.22);
  color: var(--kean-card);
}
.hero.custom .limit-line {
  color: #ffd666;
}
/* 未登录兜底：一行 13px 辅助灰，位置压在页面中偏下（读起来像"正在跳转"），不做 hero 区。
   ⚠️ 这里**只有排版与颜色**，绝不加 backdrop-filter / transform / filter / will-change /
   contain / perspective：本页 <wd-toast> 的根节点带内联 position:fixed + translate，
   一旦祖先链上出现这些属性，toast 就会被劫持到祖先坐标系里（证据见 wd-toast.vue:107~119）。 */
.guest-hint {
  margin-top: 36vh;
  padding: 0 24px;
  text-align: center;
  color: var(--kean-muted);
  font-size: 13px;
}
.nickname {
  color: var(--kean-text);
  font-size: 20px;
  font-weight: 600;
}
.gender-tag {
  background: rgba(77, 128, 240, 0.12);
  color: var(--kean-primary-active);
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.school-line {
  margin-top: 6px;
  color: #5a6b82;
  font-size: 13px;
}
.rate-line {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--kean-sub);
  font-size: 12px;
}
.rate-line + .rate-line {
  margin-top: 2px;
}
.limit-line,
.cred-line {
  margin-top: 6px;
  color: var(--kean-muted);
  font-size: 11px;
}
.limit-line {
  color: #d48806;
}
.profile-row {
  display: flex;
  padding: 8px 20px 0;
  align-items: center;
}
.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--kean-primary-soft);
  margin-right: 14px;
  flex-shrink: 0;
}
.avatar-text {
  color: var(--kean-primary);
  font-size: 24px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.profile-main {
  flex: 1;
  min-width: 0;
}
.name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stats {
  margin: -28px 16px 0;
  background: var(--kean-card);
  border-radius: 16px;
  display: flex;
  padding: 16px 0;
  box-shadow: 0 8px 20px rgba(77, 128, 240, 0.08);
  position: relative;
  z-index: 1;
}
.page.skinned .stats {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.34) 0%, rgba(255, 255, 255, 0.08) 100%);
  border: 1px solid rgba(255, 255, 255, 0.55);
  box-shadow: none;
}
.page.skinned .num {
  color: #f7fbff;
}
.page.skinned .label {
  color: rgba(235, 244, 255, 0.76);
}
.stat {
  flex: 1;
  text-align: center;
}
.num {
  font-size: 22px;
  font-weight: 700;
  color: var(--kean-text);
}
.label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kean-muted);
}
.section {
  margin-top: 16px;
  padding: 0 16px;
}
.ongoing-wrap {
  margin-top: 16px;
}
.ongoing-error {
  padding: 0 18px 8px;
  color: #d94b4b;
  font-size: 12px;
}
.section-title {
  font-size: 13px;
  color: var(--kean-muted);
  margin: 0 4px 8px;
}
.cell-icon {
  margin-right: 8px;
  font-size: 16px;
}
</style>
