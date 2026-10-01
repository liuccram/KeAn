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
import { classCountdown, publisherTrustLine, applicantTrustLine } from "@/utils/taskAction";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useNowTick } from "@/composables/useNowTick";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { useToast } from "wot-design-uni";
import { computed, nextTick, ref } from "vue";

const toast = useToast();
const userStore = useUserStore();
const nowTick = useNowTick();
const task = ref<TaskItem | null>(null);
const applications = ref<ApplicationItem[]>([]);
const loading = ref(true);
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

const showTrust = computed(() => Boolean(task.value && !task.value.mine && task.value.publisher));

const trustLine = computed(() => publisherTrustLine(task.value?.publisher));
const showApplicantTrust = computed(() => Boolean(task.value?.mine && task.value.applicant));
const applicantTrustLineText = computed(() => applicantTrustLine(task.value?.applicant));
const countdownText = computed(() => (task.value ? classCountdown(task.value, nowTick.value) : ""));

function applicantCardTrust(item: ApplicationItem) {
  return applicantTrustLine(item);
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
    return { visible: true, label: "为对方打星", hint: "", type: "primary" as const, key: "review", more: true };
  }
  const pendingApps = isPublisher.value && task.value.status === "APPLYING" && applications.value.some((item) => item.status === "PENDING");
  if (pendingApps) {
    return { visible: true, label: "处理申请", hint: "在上方选择代课人", type: "primary" as const, key: "applicants", more: true };
  }
  if (showMatchGuide.value && canChat.value) {
    return { visible: true, label: "私聊确认教室", hint: photoWindowHint.value || "先对好教室和见面点", type: "primary" as const, key: "chat", more: true };
  }
  if (canChat.value) {
    return {
      visible: true,
      label: task.value.mine ? "私聊代课者" : "私聊发布者",
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
    rows.push({ label: task.value?.mine ? "私聊代课者" : "私聊发布者", run: handleChat });
  }
  if (canReportPublisher.value) {
    rows.push({ label: "举报该代课", run: () => goReportPage("TASK", task.value?.id, task.value?.courseName) });
    rows.push({ label: "举报发布者", run: () => goReportPage("USER", task.value?.publisher?.id, task.value?.publisher?.nickname) });
    rows.push({ label: "拉黑发布者", run: () => handleBlock(task.value?.publisher?.id, task.value?.publisher?.nickname) });
  }
  if (canReportApplicant.value) {
    rows.push({ label: "举报代课者", run: () => goReportPage("USER", task.value?.matchedApplicantId, task.value?.matchedApplicantNickname) });
    rows.push({ label: "拉黑代课者", run: () => handleBlock(task.value?.matchedApplicantId, task.value?.matchedApplicantNickname) });
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
    return;
  }
  if (!quiet) {
    loading.value = true;
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
    if (!quiet) {
      toast.error((error as Error).message || "加载失败");
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
      content: "建议先私聊确认教室和见面点",
      confirmText: "去私聊",
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
    toast.success("已确认完成，已通知对方打星");
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
    toast.error((error as Error).message || "发起私聊失败");
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
    toast.error("暂无可拉黑对象");
    return;
  }
  uni.showModal({
    title: "拉黑",
    content: `拉黑后将无法与 ${nickname || "对方"} 申请或私聊，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      submitting.value = true;
      try {
        await blockUser(userId);
        toast.success("已加入黑名单");
      } catch (error) {
        toast.error((error as Error).message || "拉黑失败");
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
    <view v-if="task" class="content">
      <view class="hero">
        <view class="title-row">
          <view class="title">{{ task.courseName }}</view>
          <text v-if="!task.mine" class="fav" @click="toggleFavorite">{{ task.favorited ? "★ 已收藏" : "☆ 收藏" }}</text>
        </view>
        <view class="status">{{ TASK_STATUS_TEXT[task.status] || task.status }}</view>
        <view v-if="countdownText" class="count">{{ countdownText }}</view>
      </view>
      <view v-if="showMatchGuide" id="focus-guide" class="guide">
        <view class="guide-title">已匹配</view>
        <view class="guide-text">建议先私聊确认教室和见面点，避免到场对不上。</view>
      </view>
      <view v-if="showTrust" class="trust">
        <view class="trust-title">发布者可信度</view>
        <view class="trust-line">{{ trustLine }}</view>
      </view>
      <view v-if="showApplicantTrust" class="trust">
        <view class="trust-title">申请者可信度</view>
        <view class="trust-line">{{ applicantTrustLineText }}</view>
      </view>
      <view v-if="task.mine && applications.length" id="focus-applicants" class="apps">
        <view class="apps-title">申请人</view>
        <view v-for="item in applications" :key="item.id" class="app-card">
          <view class="app-top">
            <text class="name">{{ item.nickname || "同学" }}</text>
            <text class="app-status">{{ APPLICATION_STATUS_TEXT[item.status] || item.status }}</text>
          </view>
          <view v-if="applicantCardTrust(item) && !(showApplicantTrust && item.status === 'ACCEPTED')" class="trust-line">{{ applicantCardTrust(item) }}</view>
          <view class="msg">{{ item.message || "无留言" }}</view>
          <view v-if="item.status === 'PENDING' || item.status === 'ACCEPTED'" class="app-actions">
            <wd-button v-if="item.status === 'PENDING' && task.status === 'APPLYING'" size="small" type="primary" :disabled="submitting" @click="handleAccept(item.id)">接受</wd-button>
            <wd-button v-if="item.status === 'PENDING' && task.status === 'APPLYING'" size="small" plain :disabled="submitting" @click="handleReject(item.id)">拒绝</wd-button>
            <wd-button size="small" plain :disabled="submitting" @click="handleChatPeer(item.applicantId)">私聊</wd-button>
          </view>
        </view>
      </view>
      <wd-cell-group border title="上课信息">
        <wd-cell title="日期" :value="task.taskDate" />
        <wd-cell title="时间" :value="`${task.startTime} - ${task.endTime}`" />
        <wd-cell title="校区" :value="task.campusName || '-'" />
        <wd-cell title="地点" :value="`${task.building} ${task.classroom}`" />
        <wd-cell title="是否上机" :value="task.computerLab === 1 ? '是' : '否'" />
        <wd-cell title="是否拍照" :value="task.requirePhoto === 1 ? '是' : '否'" />
        <wd-cell title="性别要求" :value="genderRequirementLabel(task.genderRequirement)" />
        <wd-cell title="酬谢" :value="formatReward(task.reward)" />
        <wd-cell title="结算说明" value="酬谢仅展示，线下自行结算，平台不代收" />
        <wd-cell title="申请人数" :value="`${task.applyCount} 人`" />
        <wd-cell title="发布时间" :value="parseDateTime(task.createdAt)" />
      </wd-cell-group>
      <wd-cell-group border title="发布者">
        <wd-cell title="昵称" :value="task.publisher?.nickname || '-'" />
        <wd-cell title="性别" :value="genderLabel(task.publisher?.gender)" />
        <wd-cell title="学校" :value="task.publisher?.schoolName || '-'" />
        <wd-cell title="校区" :value="task.publisher?.campusName || '-'" />
        <wd-cell title="发布完成" :value="`${task.publisher?.completedCount ?? 0} 次`" />
        <wd-cell
          title="发布评分"
          :value="task.publisher?.ratingCount ? `${Number(task.publisher.ratingAvg).toFixed(1)} 分 · ${task.publisher.ratingCount} 次` : '暂无评分'"
        />
        <wd-cell title="取消次数" :value="String(task.publisher?.cancelledCount ?? 0)" />
        <wd-cell title="被举报" :value="String(task.publisher?.reportedCount ?? 0)" />
        <wd-cell v-if="publisherLimits.length" title="限制" :value="publisherLimits.join(' / ')" />
      </wd-cell-group>
      <wd-cell-group v-if="task.applicant" border title="代课者">
        <wd-cell title="昵称" :value="task.applicant.nickname || '-'" />
        <wd-cell title="性别" :value="genderLabel(task.applicant.gender)" />
        <wd-cell title="学校" :value="task.applicant.schoolName || '-'" />
        <wd-cell title="校区" :value="task.applicant.campusName || '-'" />
        <wd-cell title="代课完成" :value="`${task.applicant.completedCount ?? 0} 次`" />
        <wd-cell
          title="代课评分"
          :value="task.applicant.ratingCount ? `${Number(task.applicant.ratingAvg).toFixed(1)} 分 · ${task.applicant.ratingCount} 次` : '暂无评分'"
        />
        <wd-cell title="取消次数" :value="String(task.applicant.cancelledCount ?? 0)" />
        <wd-cell title="被举报" :value="String(task.applicant.reportedCount ?? 0)" />
        <wd-cell v-if="applicantLimits.length" title="限制" :value="applicantLimits.join(' / ')" />
      </wd-cell-group>
      <wd-cell-group border title="说明">
        <wd-cell title="原因" :value="task.reason || '未填写'" />
        <wd-cell title="要求" :value="task.requirement || '未填写'" />
        <wd-cell title="备注" :value="task.remark || '未填写'" />
      </wd-cell-group>
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
        <image class="fulfill" :src="resolveMediaUrl(task.fulfillPhotoUrl)" mode="widthFix" @click="previewFulfill" />
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
    <wd-status-tip v-if="!task && !loading" image="content" tip="任务不存在" />
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 108px;
}
.count {
  margin-top: 6px;
  color: #f77234;
  font-size: 13px;
  font-weight: 600;
}
.guide,
.trust,
.apps,
.apply-box,
.photo-box {
  margin: 12px 16px 0;
  background: #fff;
  border-radius: 12px;
  padding: 12px 16px;
}
.guide {
  border-left: 3px solid #4d80f0;
}
.guide-title {
  font-size: 14px;
  font-weight: 700;
  color: #1d2129;
}
.guide-text {
  margin-top: 4px;
  font-size: 13px;
  color: #4e5969;
  line-height: 1.5;
}
.trust-title {
  font-size: 13px;
  color: #86909c;
  margin-bottom: 6px;
}
.trust-line {
  font-size: 13px;
  color: #1d2129;
  line-height: 1.5;
}
.hero {
  padding: 20px 16px 8px;
}
.title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.title {
  font-size: 22px;
  font-weight: 600;
  color: #1d2129;
  flex: 1;
}
.fav {
  color: #f7ba2a;
  font-size: 13px;
  flex-shrink: 0;
}
.status {
  margin-top: 6px;
  color: #4d80f0;
  font-size: 13px;
}
.fulfill {
  width: 100%;
  border-radius: 8px;
  margin-top: 4px;
}
.apps-title {
  font-size: 14px;
  color: #86909c;
  margin-bottom: 8px;
}
.limit-tip {
  color: #f53f3f;
  font-size: 13px;
  line-height: 1.6;
}
.app-card {
  padding: 10px 0;
  border-bottom: 1px solid #f2f3f5;
}
.app-card:last-child {
  border-bottom: none;
}
.app-top {
  display: flex;
  justify-content: space-between;
}
.name {
  font-weight: 600;
}
.app-card .trust-line {
  margin-top: 6px;
  color: #4e5969;
  font-size: 12px;
  font-weight: 400;
}
.app-status {
  color: #4d80f0;
  font-size: 12px;
}
.msg {
  margin-top: 6px;
  color: #4e5969;
  font-size: 13px;
}
.app-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  background: #fff;
  border-top: 1px solid #f2f3f5;
  padding: 10px 16px 10px;
}
.bar-hint {
  color: #f53f3f;
  font-size: 12px;
  line-height: 1.5;
  margin-bottom: 8px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar-main {
  flex: 1;
  min-width: 0;
}
</style>
