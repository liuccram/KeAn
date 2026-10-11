<script setup lang="ts">
import { createReport, listMyReports, listReportTypes, type ReportItem, type ReportTypeItem } from "@/api/report";
import FallbackImage from "@/components/FallbackImage.vue";
import PageIntro from "@/components/PageIntro.vue";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const submitting = ref(false);
const alreadyReported = ref(false);
const types = ref<ReportTypeItem[]>([]);
const images = ref<string[]>([]);
const imageKeys = ref<string[]>([]);
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const model = ref({
  targetType: "USER",
  targetId: 0,
  targetLabel: "",
  type: "OTHER",
  description: ""
});

const typeColumns = computed(() => types.value.map((item) => ({ label: item.label, value: item.value })));
const targetText = computed(() => {
  const label = model.value.targetLabel || `#${model.value.targetId}`;
  if (model.value.targetType === "TASK") {
    return `代课：${label}`;
  }
  if (model.value.targetType === "MESSAGE") {
    return `聊天消息：${label}`;
  }
  return `用户：${label}`;
});

function isBlocked(item: ReportItem) {
  if (item.targetType !== model.value.targetType || Number(item.targetId) !== Number(model.value.targetId)) {
    return false;
  }
  if (model.value.targetType === "TASK") {
    return true;
  }
  return item.status === "PENDING" || item.status === "PROCESSING";
}

async function refreshLimit() {
  if (!model.value.targetId) {
    return;
  }
  try {
    const list = await listMyReports();
    alreadyReported.value = (list || []).some(isBlocked);
  } catch {
    // ignore
  }
}

onLoad(async (query) => {
  model.value.targetType = String(query?.targetType || "USER").toUpperCase();
  model.value.targetId = Number(query?.targetId || 0);
  model.value.targetLabel = query?.targetLabel ? decodeURIComponent(String(query.targetLabel)) : "";
  try {
    types.value = (await listReportTypes()) || [];
    if (types.value.length && !types.value.some((item) => item.value === model.value.type)) {
      model.value.type = types.value[0].value;
    }
    await refreshLimit();
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
});

useLiveUpdates(() => {
  refreshLimit();
});

function chooseImage() {
  if (imageKeys.value.length >= 3) {
    toast.info("最多上传 3 张图片");
    return;
  }
  uni.chooseImage({
    count: 3 - imageKeys.value.length,
    sizeType: ["original"],
    sourceType: ["album", "camera"],
    success: async (res) => {
      for (const filePath of res.tempFilePaths || []) {
        try {
          const uploaded = await uploadFile(filePath, "REPORT", { onProgress });
          imageKeys.value = imageKeys.value.concat(uploaded.objectKey);
          images.value = images.value.concat(resolveMediaUrl(uploaded.url || uploaded.objectKey));
        } catch (error) {
          toast.error((error as Error).message || "图片上传失败");
        }
      }
      resetUpload();
    }
  });
}

function removeImage(index: number) {
  imageKeys.value = imageKeys.value.filter((_, i) => i !== index);
  images.value = images.value.filter((_, i) => i !== index);
}

async function handleSubmit() {
  if (alreadyReported.value) {
    toast.error(model.value.targetType === "TASK" ? "该代课你已举报过，不可再次举报" : "你已举报过该内容");
    return;
  }
  if (!model.value.targetId) {
    toast.error("缺少举报对象");
    return;
  }
  if (!model.value.type) {
    toast.error("请选择原因");
    return;
  }
  submitting.value = true;
  try {
    await createReport({
      targetType: model.value.targetType,
      targetId: model.value.targetId,
      type: model.value.type,
      description: model.value.description.trim() || undefined,
      images: imageKeys.value
    });
    alreadyReported.value = true;
    toast.success("举报已提交");
    setTimeout(() => uni.navigateBack(), 400);
  } catch (error) {
    const message = (error as Error).message || "提交失败";
    if (message.includes("已举报")) {
      alreadyReported.value = true;
    }
    toast.error(message);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <view class="page report-page">
    <PageIntro eyebrow="COMMUNITY CARE" title="提交举报" description="请提供真实、具体的信息，便于及时核查" marker="守护" />
    <view class="card report-compose">
      <view class="target-block"><text class="target-label">举报对象</text><text class="target">{{ targetText }}</text></view>
      <view class="field-caption">选择最符合的原因</view><wd-picker v-model="model.type" label="举报原因" :columns="typeColumns" />
      <view class="field-caption">补充情况（选填）</view><wd-textarea v-model="model.description" placeholder="描述发生的情况、时间或相关细节" :maxlength="500" />
      <view class="evidence-heading"><view><text class="evidence-title">相关图片</text><text class="evidence-sub">最多上传 3 张，帮助管理员了解情况</text></view><text class="evidence-count">{{ images.length }}/3</text></view>
      <view class="images">
        <FallbackImage v-for="(src, index) in images" :key="src" class="shot" :src="src" mode="aspectFill" @click="removeImage(index)" />
        <view v-if="uploading" class="add uploading">{{ uploadLabel }}</view>
        <view v-else-if="images.length < 3" class="add" @click="chooseImage">+ 证据图</view>
      </view>
      <view v-if="alreadyReported" class="limit">该对象当前不可再次举报</view>
      <wd-button type="primary" block :loading="submitting" :disabled="alreadyReported || uploading" @click="handleSubmit">
        {{ uploading ? uploadLabel : "提交举报" }}
      </wd-button>
    </view>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
}
.card {
  background: var(--kean-card);
  margin: 12px 16px;
  border: 1px solid rgba(224,232,243,.9);
  border-radius: 17px;
  padding: 16px;
  box-shadow: 0 5px 16px rgba(34,59,97,.04);
}
.target-block { display:flex; flex-direction:column; gap:6px; margin-bottom:12px; padding:12px; border-radius:12px; background:rgba(241,246,253,.86); }.target-label { color:var(--kean-muted); font-size:10px; }
.field-caption { margin:13px 0 7px; color:var(--kean-sub); font-size:12px; font-weight:600; }.evidence-heading { display:flex; align-items:center; justify-content:space-between; margin-top:14px; }.evidence-title { display:block; color:var(--kean-sub); font-size:12px; font-weight:600; }.evidence-sub { display:block; margin-top:4px; color:var(--kean-muted); font-size:10px; }.evidence-count { color:var(--kean-muted); font-size:10px; }
.title {
  font-weight: 600;
  color: var(--kean-text);
  margin-bottom: 8px;
}
.target {
  margin:0;
  padding:0;
  background:transparent;
  border-radius:0;
  color: var(--kean-text);
  font-size: 13px;
  font-weight:600;
}
.limit {
  margin: 8px 0 12px;
  color: var(--kean-danger);
  font-size: 13px;
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.shot,
.add {
  width: 80px;
  height: 80px;
  border-radius: 12px;
  background: var(--kean-line);
}
.add {
  color: var(--kean-muted);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.add.uploading {
  color: #3b82f6;
  font-size: 11px;
  text-align: center;
  padding: 0 4px;
  box-sizing: border-box;
}
</style>
