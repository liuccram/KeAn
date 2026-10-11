<script setup lang="ts">
import {
  acceptApplication,
  applyTask,
  listApplications,
  rejectApplication,
  withdrawApplication,
  APPLICATION_STATUS_TEXT,
  type ApplicationItem
} from "@/api/application";
import {
  cancelTask,
  completeTask,
  confirmTask,
  getTask,
  TASK_STATUS_TEXT,
  type TaskItem
} from "@/api/task";
import { fetchMe } from "@/api/auth";
import { openChat } from "@/api/chat";
import { blockUser } from "@/api/blacklist";
import { addFavorite, removeFavorite } from "@/api/favorite";
import { useUserStore } from "@/store/user";
import { actionBlockReason, cancelLockReason, formatReward, fulfillPhotoHint, genderLabel, genderRequirementLabel, parseDateTime, restrictionLabels } from "@/utils/format";
import { goReport } from "@/utils/report";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { classCountdown, applicantTrustLine } from "@/utils/taskAction";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useNowTick } from "@/composables/useNowTick";
import { useUploadProgress } from "@/composables/useUploadProgress";
import FallbackImage from "@/components/FallbackImage.vue";
import CampusHighlight from "@/components/CampusHighlight.vue";
import ListState from "@/components/ListState.vue";
import { useToast } from "wot-design-uni";
import { computed, nextTick, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const nowTick = useNowTick();
const task = ref<TaskItem | null>(null);
const applications = ref<ApplicationItem[]>([]);
const loading = ref(true);
// 只有「任务不存在」以外的失败才算加载失败：不存在是真的查不到，走空态
const loadError = ref("");
const submitting = ref(false);
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const applyMessage = ref("");
const id = ref(0);
const focus = ref("");
const focusApplied = ref(false);
const cancelOpen = ref(false);
const cancelReason = ref("");
const pendingFulfillKey = ref("");
const safeBottom = uni.getSystemInfoSync().safeAreaInsets?.bottom || 0;
const applyBlock = computed(() => actionBlockReason(userStore.state.user, "apply"));
const chatBlock = computed(() => actionBlockReason(userStore.state.user, "chat"));
const cancelBlocked = computed(() => cancelLockReason(task.value));

const canEdit = computed(() => {
  if (!task.value?.mine) {
    return false;
  }
  const status = task.value.status;
  return status === "WAITING" || status === "APPLYING" || status === "MATCHED" || status === "CONFIRMED";
});

const isPublisher = computed(() => Boolean(task.value?.mine));
const isMatchedApplicant = computed(() => Boolean(task.value?.matchedApplicant));
const needPhoto = computed(() => task.value?.requirePhoto === 1);
const bothFulfilled = computed(() => {
  return task.value?.applicantConfirmed === 1;
});
const publisherLimits = computed(() => restrictionLabels(task.value?.publisher));
const applicantLimits = computed(() => restrictionLabels(task.value?.applicant));

const canApply = computed(() => {
  if (!task.value || task.value.mine || !userStore.isLoggedIn.value || applyBlock.value) {
    return false;
  }
  const open = task.value.status === "WAITING" || task.value.status === "APPLYING";
  const status = task.value.myApplicationStatus;
  return open && (!status || status === "CANCELLED");
});

const showApplyBlocked = computed(() => {
  if (!task.value || task.value.mine || !userStore.isLoggedIn.value || !applyBlock.value) {
    return false;
  }
  const open = task.value.status === "WAITING" || task.value.status === "APPLYING";
  const status = task.value.myApplicationStatus;
  return open && (!status || status === "CANCELLED");
});

const needLoginToApply = computed(() => {
  if (!task.value || task.value.mine || userStore.isLoggedIn.value) {
    return false;
  }
  return task.value.status === "WAITING" || task.value.status === "APPLYING";
});

const canWithdraw = computed(() => task.value?.myApplicationStatus === "PENDING");

const canConfirm = computed(() => {
  if (!needPhoto.value || !task.value || !isMatchedApplicant.value || task.value.applicantConfirmed === 1) {
    return false;
  }
  const status = task.value.status;
  if (status !== "MATCHED" && status !== "CONFIRMED" && status !== "IN_PROGRESS") {
    return false;
  }
  return !fulfillPhotoHint(task.value);
});

const photoWindowHint = computed(() => {
  if (!needPhoto.value || !task.value || !isMatchedApplicant.value || task.value.applicantConfirmed === 1) {
    return "";
  }
  const status = task.value.status;
  if (status !== "MATCHED" && status !== "CONFIRMED" && status !== "IN_PROGRESS") {
    return "";
  }
  return fulfillPhotoHint(task.value);
});

function classEnded(item?: { endAt?: string | null } | null) {
  if (!item?.endAt) {
    return false;
  }
  const end = new Date(item.endAt.replace(" ", "T")).getTime();
  return !Number.isNaN(end) && Date.now() >= end;
}

const canComplete = computed(() => {
  if (!task.value || task.value.status !== "IN_PROGRESS" || !classEnded(task.value)) {
    return false;
  }
  if (needPhoto.value && !bothFulfilled.value) {
    return false;
  }
  return isPublisher.value || isMatchedApplicant.value;
});

const canCancel = computed(() => {
  if (!task.value || cancelBlocked.value) {
    return false;
  }
  const status = task.value.status;
  if (status === "WAITING" || status === "APPLYING") {
    return isPublisher.value;
  }
  if (status === "MATCHED" || status === "CONFIRMED") {
    return isPublisher.value || isMatchedApplicant.value;
  }
  return false;
});

const showCancelLocked = computed(() => {
  if (!task.value || !cancelBlocked.value) {
    return false;
  }
  const status = task.value.status;
  return (status === "MATCHED" || status === "CONFIRMED") && (isPublisher.value || isMatchedApplicant.value);
});

const photoStatusText = computed(() => {
  if (!task.value) {
    return "";
  }
  const uploaded = task.value.applicantConfirmed === 1;
  const name = task.value.matchedApplicantNickname || "代课者";
  if (isMatchedApplicant.value) {
    return uploaded ? "你已上传照片" : "你未上传照片";
  }
  return uploaded ? `代课者 ${name} 已上传照片` : `代课者 ${name} 未上传照片`;
});

const publisherPhotoHint = computed(() => {
  if (isMatchedApplicant.value) {
    return "发布者无需拍照，下课任一方确认即可完成";
  }
  return "你无需拍照，下课任一方确认即可完成";
});

const canChat = computed(() => {
  if (!task.value || !userStore.isLoggedIn.value) {
    return false;
  }
  if (isPublisher.value) {
    return Boolean(task.value.matchedApplicantId);
  }
  const applyStatus = task.value.myApplicationStatus;
  return applyStatus === "PENDING" || applyStatus === "ACCEPTED" || isMatchedApplicant.value;
});

const showMatchGuide = computed(() => {
  if (!task.value) {
    return false;
  }
  const status = task.value.status;
  if (status !== "MATCHED" && status !== "CONFIRMED") {
    return false;
  }
  return isPublisher.value || isMatchedApplicant.value;
});

const countdownText = computed(() => (task.value ? classCountdown(task.value, nowTick.value) : ""));

function applicantCardTrust(item: ApplicationItem) {
  return applicantTrustLine(item);
}

function openProfile(userId?: number | null) {
  if (userId) {
    uni.navigateTo({ url: `/pages/mine/user?id=${userId}` });
  }
}

const actionBar = computed(() => {
  const hidden = { visible: false, label: "", hint: "", type: "primary" as const, key: "", more: false };
  if (!task.value || cancelOpen.value) {
    return hidden;
  }
  const more = canEdit.value || canWithdraw.value || canChat.value || canReportPublisher.value || canReportApplicant.value || canCancel.value;
  if (needLoginToApply.value) {
    return { visible: true, label: "登录后申请", hint: "", type: "primary" as const, key: "login", more: false };
  }
  if (showApplyBlocked.value) {
    return { visible: true, label: "", hint: applyBlock.value, type: "primary" as const, key: "", more };
  }
  if (canApply.value) {
    return { visible: true, label: "申请代课", hint: "", type: "primary" as const, key: "apply", more };
  }
  if (canConfirm.value) {
    return {
      visible: true,
      label: pendingFulfillKey.value ? "照片已上传，点击确认到场" : "上传现场照片确认到场",
      hint: "",
      type: "primary" as const,
      key: "photo",
      more: true
    };
  }
  if (canComplete.value) {
    return { visible: true, label: "下课确认完成", hint: "", type: "primary" as const, key: "complete", more: true };
  }
  if (task.value.status === "COMPLETED" && task.value.canReview) {
    return { visible: true, label: "为对方评价", hint: "", type: "primary" as const, key: "review", more: true };
  }
  const pendingApps = isPublisher.value && task.value.status === "APPLYING" && applications.value.some((item) => item.status === "PENDING");
  if (pendingApps) {
    return { visible: true, label: "处理申请", hint: "在上方选择代课人", type: "primary" as const, key: "applicants", more: true };
  }
  if (showMatchGuide.value && canChat.value) {
    return { visible: true, label: "私信确认教室", hint: photoWindowHint.value || "请先与对方确认教室和见面地点", type: "primary" as const, key: "chat", more: true };
  }
  if (canChat.value) {
    return {
      visible: true,
      label: task.value.mine ? "私信代课者" : "私信发布者",
      hint: photoWindowHint.value,
      type: "primary" as const,
      key: "chat",
      more: true
    };
  }
  if (canWithdraw.value) {
    return { visible: true, label: "撤回申请", hint: "", type: "primary" as const, key: "withdraw", more: true };
  }
  if (canEdit.value) {
    return { visible: true, label: "编辑", hint: showCancelLocked.value ? cancelBlocked.value : "", type: "primary" as const, key: "edit", more };
  }
  if (more || photoWindowHint.value || showCancelLocked.value) {
    return { visible: true, label: "", hint: photoWindowHint.value || cancelBlocked.value, type: "primary" as const, key: "", more };
  }
  return hidden;
});

function runPrimary() {
  switch (actionBar.value.key) {
    case "login":
      goLogin();
      return;
    case "apply":
      handleApply();
      return;
    case "photo":
      handleConfirm();
      return;
    case "complete":
      handleComplete();
      return;
    case "review":
      goRate();
      return;
    case "applicants":
      scrollTo("focus-applicants");
      return;
    case "chat":
      handleChat();
      return;
    case "withdraw":
      handleWithdraw();
      return;
    case "edit":
      goEdit();
      return;
    default:
      return;
  }
}

function scrollTo(anchor: string) {
  setTimeout(() => {
    uni.pageScrollTo({ selector: `#${anchor}`, duration: 240 });
  }, 80);
}

function applyFocus() {
  const key = focus.value;
  if (!key || !task.value) {
    return;
  }
  if (key === "review" && task.value.canReview) {
    goRate();
    return;
  }
  const map: Record<string, string> = {
    applicants: "focus-applicants",
    photo: "focus-photo",
    complete: "focus-complete",
    chat: "focus-guide",
    apply: "focus-apply"
  };
  const anchor = map[key];
  if (anchor) {
    nextTick(() => scrollTo(anchor));
  }
}

function openMore() {
  const rows: { label: string; run: () => void }[] = [];
  const primary = actionBar.value.key;
  if (canEdit.value && primary !== "edit") {
    rows.push({ label: "编辑", run: goEdit });
  }
  if (canWithdraw.value && primary !== "withdraw") {
    rows.push({ label: "撤回申请", run: handleWithdraw });
  }
  if (canChat.value && primary !== "chat") {
    rows.push({ label: task.value?.mine ? "私信代课者" : "私信发布者", run: handleChat });
  }
  if (canReportPublisher.value) {
    rows.push({ label: "举报该代课", run: () => goReportPage("TASK", task.value?.id, task.value?.courseName) });
    rows.push({ label: "举报发布者", run: () => goReportPage("USER", task.value?.publisher?.id, task.value?.publisher?.nickname) });
    rows.push({ label: "将发布者加入黑名单", run: () => handleBlock(task.value?.publisher?.id, task.value?.publisher?.nickname) });
  }
  if (canReportApplicant.value) {
    rows.push({ label: "举报代课者", run: () => goReportPage("USER", task.value?.matchedApplicantId, task.value?.matchedApplicantNickname) });
    rows.push({ label: "将代课者加入黑名单", run: () => handleBlock(task.value?.matchedApplicantId, task.value?.matchedApplicantNickname) });
  }
  if (canCancel.value) {
    rows.push({ label: "取消任务", run: handleCancel });
  }
  if (!rows.length) {
    return;
  }
  uni.showActionSheet({
    itemList: rows.map((item) => item.label),
    success: (res) => {
      rows[res.tapIndex]?.run();
    }
  });
}

const canReportPublisher = computed(() => {
  return Boolean(userStore.isLoggedIn.value && task.value && !task.value.mine && task.value.publisher?.id);
});

const canReportApplicant = computed(() => {
  return Boolean(task.value?.mine && task.value.matchedApplicantId);
});

async function load(quiet = false) {
  if (!id.value) {
    // 没有 id 不存在「加载中」可言，直接落到「任务不存在」空态
    loading.value = false;
    return;
  }
  if (!quiet) {
    loading.value = true;
    // 后台静默刷新不清失败原因：清掉会让「加载失败」闪成「任务不存在」
    loadError.value = "";
  }
  try {
    if (userStore.isLoggedIn.value) {
      try {
        const latest = await fetchMe();
        if (userStore.state.token) {
          userStore.setLogin(userStore.state.token, latest);
        }
      } catch {
        // 使用本地缓存
      }
    }
    task.value = await getTask(id.value);
    loadError.value = "";
    if (task.value.applicantConfirmed === 1) {
      pendingFulfillKey.value = "";
    }
    if (task.value.mine && ["WAITING", "APPLYING", "MATCHED", "CONFIRMED", "IN_PROGRESS", "COMPLETED"].includes(task.value.status)) {
      applications.value = await listApplications(id.value);
    } else {
      applications.value = [];
    }
    if (!quiet && !focusApplied.value) {
      applyFocus();
      focusApplied.value = true;
    }
  } catch (error) {
    const message = (error as Error).message || "加载失败";
    // 「不存在」是真的查不到，保留原空态；其它失败用 ListState 显示「加载失败 + 重新加载」
    if (!message.includes("不存在")) {
      loadError.value = message;
    }
    // 已经有内容时只用轻提示：这时若让失败态顶掉详情，比不提示更糟。
    if (!quiet && task.value) {
      toast.error(message);
    }
  } finally {
    if (!quiet) {
      loading.value = false;
    }
  }
}

function goLogin() {
  uni.navigateTo({ url: "/pages/auth/login" });
}

function goEdit() {
  uni.navigateTo({ url: `/pages/task/edit?id=${id.value}` });
}

function goRate() {
  uni.navigateTo({ url: `/pages/task/rate?id=${id.value}` });
}

async function handleWithdraw() {
  if (!task.value?.myApplicationId) {
    toast.error("没有可撤回的申请");
    return;
  }
  submitting.value = true;
  try {
    task.value = await withdrawApplication(task.value.myApplicationId);
    toast.success("已撤回申请");
  } catch (error) {
    toast.error((error as Error).message || "撤回失败");
  } finally {
    submitting.value = false;
  }
}

async function handleApply() {
  if (!userStore.isLoggedIn.value) {
    goLogin();
    return;
  }
  if (applyBlock.value) {
    toast.error(applyBlock.value);
    return;
  }
  submitting.value = true;
  try {
    task.value = await applyTask(id.value, applyMessage.value.trim() || undefined);
    toast.success("已申请");
    applyMessage.value = "";
  } catch (error) {
    toast.error((error as Error).message || "申请失败");
  } finally {
    submitting.value = false;
  }
}

async function handleAccept(appId: number) {
  submitting.value = true;
  try {
    task.value = await acceptApplication(appId);
    toast.success("已选择代课人");
    await load();
    uni.showModal({
      title: "已匹配",
      content: "建议先与对方确认教室和见面地点",
      confirmText: "去发消息",
      cancelText: "稍后",
      success: (res) => {
        if (res.confirm) {
          handleChat();
        }
      }
    });
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  } finally {
    submitting.value = false;
  }
}

async function handleReject(appId: number) {
  submitting.value = true;
  try {
    task.value = await rejectApplication(appId);
    toast.success("已拒绝");
    await load();
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm() {
  if (pendingFulfillKey.value) {
    await submitFulfill(pendingFulfillKey.value);
    return;
  }
  uni.chooseImage({
    count: 1,
    sizeType: ["original"],
    sourceType: ["album", "camera"],
    success: async (res) => {
      const filePath = res.tempFilePaths?.[0];
      if (!filePath) {
        return;
      }
      submitting.value = true;
      try {
        const uploaded = await uploadFile(filePath, "FULFILL", { onProgress });
        pendingFulfillKey.value = uploaded.objectKey;
      } catch (error) {
        toast.error((error as Error).message || "上传失败");
        submitting.value = false;
        return;
      } finally {
        resetUpload();
      }
      await submitFulfill(pendingFulfillKey.value);
    }
  });
}

async function submitFulfill(objectKey: string) {
  submitting.value = true;
  try {
    task.value = await confirmTask(id.value, objectKey);
    pendingFulfillKey.value = "";
    toast.success("已上传履约照片，下课后再确认完成");
    await load();
  } catch (error) {
    pendingFulfillKey.value = objectKey;
    toast.error((error as Error).message || "照片已上传，确认失败，请重试");
  } finally {
    submitting.value = false;
  }
}

async function handleComplete() {
  submitting.value = true;
  try {
    task.value = await completeTask(id.value);
    toast.success("已确认完成，已通知对方评价");
    uni.navigateTo({ url: `/pages/task/rate?id=${id.value}` });
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  } finally {
    submitting.value = false;
  }
}

async function handleChat() {
  if (!task.value) {
    return;
  }
  const peerId = task.value.mine ? task.value.matchedApplicantId : task.value.publisherId;
  await startChat(peerId);
}

async function handleChatPeer(peerId?: number | null) {
  await startChat(peerId);
}

async function startChat(peerId?: number | null) {
  if (chatBlock.value) {
    toast.error(chatBlock.value);
    return;
  }
  if (!peerId) {
    toast.error("暂无可聊对象");
    return;
  }
  submitting.value = true;
  try {
    const session = await openChat(peerId);
    uni.navigateTo({ url: `/pages/message/chat?id=${session.id}` });
  } catch (error) {
    toast.error((error as Error).message || "发起私信失败");
  } finally {
    submitting.value = false;
  }
}

function handleCancel() {
  cancelReason.value = "";
  cancelOpen.value = true;
}

async function confirmCancel() {
  const reason = cancelReason.value.trim();
  if (reason.length < 2) {
    toast.error("请填写至少 2 个字的取消原因");
    return;
  }
  submitting.value = true;
  try {
    task.value = await cancelTask(id.value, reason);
    cancelOpen.value = false;
    toast.success("已取消");
  } catch (error) {
    toast.error((error as Error).message || "取消失败");
  } finally {
    submitting.value = false;
  }
}

function previewFulfill() {
  const src = resolveMediaUrl(task.value?.fulfillPhotoUrl);
  if (!src) {
    return;
  }
  uni.previewImage({ urls: [src] });
}

async function toggleFavorite() {
  if (!userStore.isLoggedIn.value) {
    uni.navigateTo({ url: "/pages/auth/login" });
    return;
  }
  if (!task.value) {
    return;
  }
  if (task.value.mine) {
    toast.error("不能收藏自己发布的代课");
    return;
  }
  submitting.value = true;
  try {
    if (task.value.favorited) {
      await removeFavorite(task.value.id);
      toast.success("已取消收藏");
    } else {
      await addFavorite(task.value.id);
      toast.success("已收藏");
    }
    await load();
  } catch (error) {
    toast.error((error as Error).message || "操作失败");
  } finally {
    submitting.value = false;
  }
}

function goReportPage(targetType: string, targetId?: number | null, targetLabel?: string | null) {
  if (!goReport(targetType, targetId, targetLabel)) {
    toast.error("暂无可举报对象");
  }
}

function handleBlock(userId?: number | null, nickname?: string | null) {
  if (!userId) {
    toast.error("暂无可加入黑名单的对象");
    return;
  }
  uni.showModal({
    title: "加入黑名单",
    content: `加入黑名单后，对方将无法再向你申请任务，你们也无法互发消息，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      submitting.value = true;
      try {
        await blockUser(userId);
        toast.success("已加入黑名单");
      } catch (error) {
        toast.error((error as Error).message || "加入黑名单失败");
      } finally {
        submitting.value = false;
      }
    }
  });
}

onLoad((query) => {
  id.value = Number(query?.id || 0);
  focus.value = String(query?.focus || "");
  if (!id.value) {
    toast.error("任务不存在");
  }
});

onShow(() => {
  load();
});

useLiveUpdates((event) => {
  if (!event) {
    load(true);
    return;
  }
  if (event.type !== "NOTICE") {
    return;
  }
  const sameTask = !event.bizId || event.bizId === id.value;
  if ((event.bizType === "TASK" || event.noticeType === "APPLICATION" || event.noticeType === "TASK") && sameTask) {
    load(true);
  }
});
</script>

<template>
  <view class="page">
    <!-- 没有内容可显示时才由 ListState 接管：加载中 / 加载失败+重新加载 / 任务不存在 -->
    <ListState
      :loading="loading"
      :error="loadError"
      :empty="!task"
      empty-text="任务不存在"
      @retry="load()"
    />
    <view v-if="task" class="content">
      <view class="hero">
        <view class="hero-topline">
          <text class="hero-kicker">CAMPUS CLASS COVER</text>
          <wd-icon v-if="!task.mine" class="favorite-icon" :name="task.favorited ? 'star-filled' : 'star'" @click="toggleFavorite" />
        </view>
        <view class="hero-title">{{ task.courseName }}</view>
        <view class="hero-tags">
          <wd-tag round :type="['COMPLETED'].includes(task.status) ? 'success' : ['CANCELLED', 'EXPIRED'].includes(task.status) ? 'default' : ['MATCHED', 'CONFIRMED', 'IN_PROGRESS'].includes(task.status) ? 'warning' : 'primary'">{{ TASK_STATUS_TEXT[task.status] || task.status }}</wd-tag>
          <wd-tag round plain type="default">{{ task.applyCount }} 位同学申请</wd-tag>
          <wd-tag v-if="countdownText" round plain type="warning">{{ countdownText }}</wd-tag>
        </view>
        <view class="hero-bottom"><text>求助编号 #{{ task.id }}</text><text>发布于 {{ parseDateTime(task.createdAt) }}</text></view>
      </view>
      <view class="schedule-card">
        <view class="section-eyebrow">CLASS PLAN <text>上课安排</text></view>
        <view class="schedule-date"><text class="date-icon">日</text><view><text class="date-main">{{ task.taskDate }}</text><text class="date-sub">{{ task.startTime }} 至 {{ task.endTime }}</text></view></view>
        <view class="schedule-divider" />
        <view class="place-row"><view class="place-icon">地</view><view class="place-copy"><view class="place-main"><CampusHighlight v-if="task.campusName" :name="task.campusName" /><text v-else>校区待补充</text></view><text class="place-sub">{{ task.building || '教学楼待补充' }} · {{ task.classroom || '教室待补充' }}</text></view></view>
        <view class="requirement-tags">
          <wd-tag round plain type="primary">{{ task.computerLab === 1 ? '需要上机' : '无需上机' }}</wd-tag>
          <wd-tag round plain :type="task.requirePhoto === 1 ? 'warning' : 'default'">{{ task.requirePhoto === 1 ? '需要现场照片' : '无需拍照' }}</wd-tag>
          <wd-tag round plain type="default">{{ genderRequirementLabel(task.genderRequirement) }}</wd-tag>
        </view>
        <view class="reward-strip"><view><text class="reward-label">感谢酬谢</text><text class="reward-note">由双方自行沟通结算</text></view><text class="reward-value">{{ formatReward(task.reward) }}</text></view>
      </view>
      <view v-if="showMatchGuide" id="focus-guide" class="guide">
        <view class="guide-title">已匹配</view>
        <view class="guide-text">建议先与对方确认教室和见面地点，避免到场后地点不一致。</view>
      </view>
      <view v-if="task.reason || task.requirement || task.remark" class="description-card">
        <view class="section-eyebrow">ABOUT THIS REQUEST <text>求助说明</text></view>
        <view v-if="task.reason" class="description-row"><text class="description-label">求助原因</text><text class="description-text">{{ task.reason }}</text></view>
        <view v-if="task.requirement" class="description-row"><text class="description-label">课堂要求</text><text class="description-text">{{ task.requirement }}</text></view>
        <view v-if="task.remark" class="description-row"><text class="description-label">补充备注</text><text class="description-text">{{ task.remark }}</text></view>
      </view>
      <view v-if="task.mine && applications.length" id="focus-applicants" class="apps">
        <view class="apps-title">申请人</view>
        <view v-for="item in applications" :key="item.id" class="app-card">
          <view class="app-top">
            <text class="name">{{ item.nickname || "同学" }}</text>
            <text class="app-status">{{ APPLICATION_STATUS_TEXT[item.status] || item.status }}</text>
          </view>
          <view v-if="applicantCardTrust(item) && item.status !== 'ACCEPTED'" class="trust-line">{{ applicantCardTrust(item) }}</view>
          <view class="msg">{{ item.message || "无留言" }}</view>
          <view v-if="item.status === 'PENDING' || item.status === 'ACCEPTED'" class="app-actions">
            <wd-button v-if="item.status === 'PENDING' && task.status === 'APPLYING'" size="small" type="primary" :disabled="submitting" @click="handleAccept(item.id)">接受</wd-button>
            <wd-button v-if="item.status === 'PENDING' && task.status === 'APPLYING'" size="small" plain :disabled="submitting" @click="handleReject(item.id)">拒绝</wd-button>
            <wd-button size="small" plain :disabled="submitting" @click="handleChatPeer(item.applicantId)">私信</wd-button>
          </view>
        </view>
      </view>
      <view class="person-card">
        <view class="section-eyebrow">TRUSTED CLASSMATE <text>发布者</text></view>
        <view class="person-main person-main--clickable" @click="openProfile(task.publisherId)">
          <FallbackImage v-if="task.publisher?.avatarUrl" class="person-avatar" :src="resolveMediaUrl(task.publisher.avatarUrl)" mode="aspectFill" />
          <view v-else class="person-avatar person-avatar--fallback">{{ (task.publisher?.nickname || '同').slice(0, 1) }}</view>
          <view class="person-copy"><text class="person-name">{{ task.publisher?.nickname || '课安同学' }}</text><text class="person-meta">{{ task.publisher?.schoolName || '学校未填写' }} · {{ task.publisher?.campusName || '校区未填写' }} · {{ genderLabel(task.publisher?.gender) }}</text></view>
          <wd-icon name="arrow-right" class="person-arrow" />
        </view>
        <view class="person-stats">
          <view><text class="person-stat-value">{{ task.publisher?.completedCount ?? 0 }}</text><text class="person-stat-label">发布完成</text></view>
          <view><text class="person-stat-value">{{ task.publisher?.ratingCount ? Number(task.publisher.ratingAvg).toFixed(1) : '—' }}</text><text class="person-stat-label">评分 · {{ task.publisher?.ratingCount || 0 }} 条</text></view>
          <view><text class="person-stat-value">{{ task.publisher?.cancelledCount ?? 0 }}</text><text class="person-stat-label">取消次数</text></view>
          <view><text class="person-stat-value">{{ task.publisher?.reportedCount ?? 0 }}</text><text class="person-stat-label">被举报</text></view>
        </view>
        <view v-if="publisherLimits.length" class="person-limits"><wd-tag v-for="label in publisherLimits" :key="label" type="warning" plain round>{{ label }}</wd-tag></view>
      </view>
      <view v-if="task.applicant" class="person-card applicant-card">
        <view class="section-eyebrow">MATCHED CLASSMATE <text>代课者</text></view>
        <view class="person-main person-main--clickable" @click="openProfile(task.applicant.id)">
          <FallbackImage v-if="task.applicant.avatarUrl" class="person-avatar" :src="resolveMediaUrl(task.applicant.avatarUrl)" mode="aspectFill" />
          <view v-else class="person-avatar person-avatar--fallback">{{ (task.applicant.nickname || '同').slice(0, 1) }}</view>
          <view class="person-copy"><text class="person-name">{{ task.applicant.nickname || '课安同学' }}</text><text class="person-meta">{{ task.applicant.schoolName || '学校未填写' }} · {{ task.applicant.campusName || '校区未填写' }} · {{ genderLabel(task.applicant.gender) }}</text></view>
        </view>
        <view class="person-stats">
          <view><text class="person-stat-value">{{ task.applicant.completedCount ?? 0 }}</text><text class="person-stat-label">代课完成</text></view>
          <view><text class="person-stat-value">{{ task.applicant.ratingCount ? Number(task.applicant.ratingAvg).toFixed(1) : '—' }}</text><text class="person-stat-label">评分 · {{ task.applicant.ratingCount || 0 }} 条</text></view>
          <view><text class="person-stat-value">{{ task.applicant.cancelledCount ?? 0 }}</text><text class="person-stat-label">取消次数</text></view>
          <view><text class="person-stat-value">{{ task.applicant.reportedCount ?? 0 }}</text><text class="person-stat-label">被举报</text></view>
        </view>
        <view v-if="applicantLimits.length" class="person-limits"><wd-tag v-for="label in applicantLimits" :key="label" type="warning" plain round>{{ label }}</wd-tag></view>
      </view>
      <wd-cell-group v-if="task.myApplicationStatus" border title="我的申请">
        <wd-cell title="状态" :value="APPLICATION_STATUS_TEXT[task.myApplicationStatus] || task.myApplicationStatus" />
      </wd-cell-group>
      <view v-if="needPhoto && (task.status === 'MATCHED' || task.status === 'CONFIRMED' || task.status === 'IN_PROGRESS')" id="focus-photo">
        <wd-cell-group border title="到场凭证">
          <wd-cell title="现场照片" :value="photoStatusText" />
          <wd-cell title="发布者" :value="publisherPhotoHint" />
        </wd-cell-group>
      </view>
      <view v-if="task.fulfillPhotoUrl" class="photo-box">
        <view class="apps-title">履约现场照片</view>
        <FallbackImage class="fulfill" :src="resolveMediaUrl(task.fulfillPhotoUrl)" mode="widthFix" @click="previewFulfill" />
      </view>
      <view v-if="task.status === 'IN_PROGRESS' || task.status === 'COMPLETED'" id="focus-complete">
        <wd-cell-group border title="完成确认">
          <wd-cell
            title="完成方式"
            :value="task.status === 'COMPLETED' ? '已完成' : needPhoto ? '代课者上传照片后，下课任一方确认即可，满 24 小时未确认则自动完成' : '无需拍照，下课任一方确认即可，满 24 小时未确认则自动完成'"
          />
        </wd-cell-group>
      </view>
      <view v-if="showApplyBlocked" id="focus-apply" class="apply-box">
        <view class="limit-tip">{{ applyBlock }}</view>
      </view>
      <view v-else-if="canApply" id="focus-apply" class="apply-box">
        <wd-textarea v-model="applyMessage" placeholder="申请留言，选填" :maxlength="500" />
      </view>
      <view v-if="cancelOpen" class="apply-box">
        <view class="apps-title">取消原因</view>
        <wd-textarea v-model="cancelReason" placeholder="请填写取消原因，至少 2 个字" :maxlength="255" />
        <wd-button type="warning" block :loading="submitting" @click="confirmCancel">确认取消</wd-button>
        <wd-button plain block :disabled="submitting" @click="cancelOpen = false">再想想</wd-button>
      </view>
    </view>
    <view v-if="task && actionBar.visible" class="action-bar" :style="{ paddingBottom: 10 + safeBottom + 'px' }">
      <view v-if="actionBar.hint" class="bar-hint">{{ actionBar.hint }}</view>
      <view class="bar-row">
        <wd-button v-if="actionBar.more" plain :disabled="submitting" @click="openMore">更多</wd-button>
        <view v-if="actionBar.label" class="bar-main">
          <wd-button :type="actionBar.type" block :loading="submitting" @click="runPrimary">{{ uploading ? uploadLabel : actionBar.label }}</wd-button>
        </view>
      </view>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page { min-height:100vh; padding-bottom:112px; background:linear-gradient(180deg,#edf4ff 0,#f5f7fb 270px,var(--kean-bg) 560px); }
.content { padding-top:1px; }
.hero { position:relative; margin:12px 16px 0; padding:22px 20px 16px; overflow:hidden; border:1px solid rgba(255,255,255,.18); border-radius:26px; color:#fff; background:radial-gradient(circle at 92% 6%,rgba(139,187,255,.48),transparent 34%),linear-gradient(145deg,#183960 0%,#245a96 55%,#467fba 100%); box-shadow:0 18px 34px rgba(36,78,128,.2); }
.hero::after { position:absolute; right:-42px; bottom:-104px; width:210px; height:210px; border:1px solid rgba(255,255,255,.13); border-radius:50%; content:""; }
.hero-topline,.hero-tags,.hero-bottom { position:relative; z-index:1; display:flex; align-items:center; }
.hero-topline { justify-content:space-between; }
.hero-kicker { color:rgba(224,238,255,.72); font-size:9px; font-weight:700; letter-spacing:1.8px; }
.favorite-icon { display:flex; align-items:center; justify-content:center; width:38px; height:38px; border:1px solid rgba(255,255,255,.22); border-radius:14px; color:#ffe2a0; background:rgba(255,255,255,.12); font-size:18px; }
.hero-title { position:relative; z-index:1; margin-top:13px; color:#fff; font-size:27px; font-weight:750; line-height:1.25; letter-spacing:.2px; }
.hero-tags { flex-wrap:wrap; gap:7px; margin-top:14px; }
.hero-tags :deep(.wd-tag),.requirement-tags :deep(.wd-tag),.person-limits :deep(.wd-tag) { margin:0; }
.hero-tags :deep(.wd-tag.is-plain) { border-color:rgba(255,255,255,.3); color:rgba(255,255,255,.9); background:rgba(255,255,255,.1); }
.hero-tags :deep(.wd-tag:not(.is-plain)) { border:0; }
.hero-bottom { justify-content:space-between; gap:8px; margin-top:17px; padding-top:11px; border-top:1px solid rgba(255,255,255,.16); color:rgba(229,240,255,.7); font-size:10px; }
.schedule-card,.description-card,.person-card,.guide,.trust,.apps,.apply-box,.photo-box { margin:13px 16px 0; padding:17px; border:1px solid rgba(222,231,243,.95); border-radius:21px; background:var(--kean-card); box-shadow:0 9px 24px rgba(34,59,97,.055); }
.section-eyebrow { display:flex; align-items:center; gap:9px; color:#86a0c1; font-size:9px; font-weight:700; letter-spacing:1.1px; }
.section-eyebrow text { color:var(--kean-sub); font-size:13px; letter-spacing:0; }
.schedule-date { display:flex; align-items:center; gap:12px; margin-top:17px; }
.date-icon,.place-icon { display:flex; flex-shrink:0; align-items:center; justify-content:center; width:43px; height:43px; border-radius:15px; color:#5682bb; background:#edf4ff; font-size:14px; font-weight:700; }
.date-main,.date-sub,.place-main,.place-sub { display:block; }
.date-main { color:#263b58; font-size:18px; font-weight:750; }
.date-sub { margin-top:4px; color:#61748d; font-size:13px; font-weight:600; }
.schedule-divider { height:1px; margin:16px 0; background:#edf0f5; }
.place-row { display:flex; align-items:center; gap:11px; }
.place-icon { color:#4d9387; background:#e9f6f1; }
.place-main { color:#2e435f; font-size:14px; font-weight:700; }
.place-sub { margin-top:4px; color:#7e8da1; font-size:11px; }
.requirement-tags { display:flex; flex-wrap:wrap; gap:7px; margin-top:16px; }
.reward-strip { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-top:16px; padding:13px 14px; border:1px solid #f5e5c6; border-radius:15px; background:linear-gradient(115deg,#fffaf0,#fff5df); }
.reward-label,.reward-note { display:block; }.reward-label { color:#9c743a; font-size:12px; font-weight:700; }.reward-note { margin-top:4px; color:#b29a72; font-size:10px; }.reward-value { color:#b66e2e; font-size:21px; font-weight:800; }
.description-row { display:flex; flex-direction:column; gap:6px; margin-top:15px; }.description-label { color:#8a9ab0; font-size:10px; font-weight:650; }.description-text { color:#435670; font-size:13px; line-height:1.75; white-space:pre-wrap; }
.guide { border-left:4px solid #5e95dc; }.guide-title { color:#2f4d70; font-size:14px; font-weight:750; }.guide-text { margin-top:5px; color:#6f8097; font-size:12px; line-height:1.65; }
.trust-line { color:#50647e; font-size:12px; line-height:1.65; }
.apps-title { margin-bottom:9px; color:#536982; font-size:14px; font-weight:700; }.limit-tip { color:var(--kean-danger); font-size:13px; line-height:1.6; }
.app-card { padding:12px 0; border-bottom:1px solid var(--kean-line); }.app-card:last-child { border-bottom:0; }.app-top { display:flex; justify-content:space-between; gap:10px; }.name { color:#2e435f; font-size:14px; font-weight:700; }.app-card .trust-line { margin-top:6px; font-size:11px; }.app-status { color:var(--kean-primary); font-size:11px; }.msg { margin-top:7px; color:var(--kean-sub); font-size:12px; line-height:1.6; }.app-actions { display:flex; gap:8px; margin-top:10px; }
.person-card { padding:17px; }.person-main { display:flex; align-items:center; gap:11px; margin-top:15px; }.person-main--clickable { cursor:pointer; }.person-avatar { width:48px; height:48px; flex-shrink:0; overflow:hidden; border-radius:17px; background:#e9f1fc; }.person-avatar--fallback { display:flex; align-items:center; justify-content:center; color:#5a82b7; font-size:18px; font-weight:700; }.person-copy { display:flex; min-width:0; flex:1; flex-direction:column; gap:4px; }.person-name { color:#293e5b; font-size:15px; font-weight:750; }.person-meta { overflow:hidden; color:#8492a5; font-size:10px; text-overflow:ellipsis; white-space:nowrap; }.person-arrow { color:#a5b2c2; font-size:13px; }
.person-stats { display:grid; grid-template-columns:repeat(4,1fr); gap:5px; margin-top:16px; padding:13px 4px 2px; border-top:1px solid #edf0f5; }.person-stats > view { display:flex; flex-direction:column; align-items:center; gap:5px; }.person-stat-value { color:#344a67; font-size:14px; font-weight:750; }.person-stat-label { color:#92a0b2; font-size:9px; }.person-limits { display:flex; flex-wrap:wrap; gap:6px; margin-top:12px; }.applicant-card { border-color:#d9ece7; }.applicant-card .person-avatar,.applicant-card .person-avatar--fallback { background:#e9f6f1; }
.content :deep(.wd-cell-group) { margin:13px 16px 0; overflow:hidden; border:1px solid rgba(222,231,243,.95); border-radius:20px; background:var(--kean-card); box-shadow:0 9px 24px rgba(34,59,97,.055); }.content :deep(.wd-cell-group__title) { justify-content:flex-start; padding:15px 17px 8px; color:#536982; font-size:13px; font-weight:700; }.content :deep(.wd-cell) { padding-right:17px; padding-left:17px; }
.fulfill { width:100%; margin-top:4px; border-radius:12px; }.apply-box :deep(.wd-textarea) { margin-bottom:12px; border-radius:13px; background:#f7f9fc; }.apply-box :deep(.wd-button) { margin-top:8px; }.action-bar { position:fixed; right:0; bottom:0; left:0; z-index:20; padding:11px 16px 10px; border-top:1px solid rgba(221,230,241,.95); background:rgba(255,255,255,.97); box-shadow:0 -8px 24px rgba(30,52,82,.07); }.bar-hint { margin-bottom:8px; color:var(--kean-danger); font-size:11px; line-height:1.5; }.bar-row { display:flex; align-items:center; gap:10px; }.bar-main { min-width:0; flex:1; }.bar-main :deep(.wd-button) { min-height:47px; border-radius:15px; font-weight:700; box-shadow:0 6px 14px rgba(74,123,190,.2); }.bar-row > :deep(.wd-button) { min-height:44px; border-radius:14px; }
</style>
