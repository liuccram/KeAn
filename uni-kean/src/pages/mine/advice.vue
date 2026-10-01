<script setup lang="ts">
import { createReport, listMyReports, listReportTypes, type ReportItem, type ReportTypeItem } from "@/api/report";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { parseDateTime } from "@/utils/format";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { useUploadProgress } from "@/composables/useUploadProgress";
import { onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const submitting = ref(false);
const types = ref<ReportTypeItem[]>([]);
const images = ref<string[]>([]);
const imageKeys = ref<string[]>([]);
// 解构到顶层，模板才会自动解包 ref
const { active: uploading, label: uploadLabel, onProgress, reset: resetUpload } = useUploadProgress();
const records = ref<ReportItem[]>([]);
const loading = ref(false);
const error = ref("");
const model = ref({
  type: "SUGGESTION",
  description: ""
});

const typeColumns = computed(() => types.value.map((item) => ({ label: item.label, value: item.value })));

const statusText: Record<string, string> = {
  PENDING: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已回复",
  REJECTED: "未采纳"
};

const resultText: Record<string, string> = {
  REPLY: "已回复",
  REJECT: "未采纳"
};

async function loadTypes() {
  try {
    types.value = (await listReportTypes("FEEDBACK")) || [];
    if (types.value.length && !types.value.some((item) => item.value === model.value.type)) {
      model.value.type = types.value[0].value;
    }
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
}

async function loadRecords() {
  // 本次请求前是否已有记录：没有时才由 ListState 显示加载中 / 失败原因和重试入口
  const first = records.value.length === 0;
  if (first) {
    loading.value = true;
  }
  error.value = "";
  try {
    const reports = await listMyReports();
    records.value = (reports || []).filter((item) => item.targetType === "FEEDBACK");
  } catch (err) {
    const message = (err as Error).message || "加载失败";
    error.value = message;
    // 已经有记录时只用轻提示，避免失败态把列表顶掉
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
  }
}

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
  const description = model.value.description.trim();
  if (!model.value.type) {
    toast.error("请选择反馈类型");
    return;
  }
  if (!description) {
    toast.error("请填写反馈内容");
    return;
  }
  submitting.value = true;
  try {
    await createReport({
      targetType: "FEEDBACK",
      targetId: 0,
      type: model.value.type,
      description,
      images: imageKeys.value
    });
    toast.success("反馈已提交");
    model.value.description = "";
    imageKeys.value = [];
    images.value = [];
    await loadRecords();
  } catch (error) {
    toast.error((error as Error).message || "提交失败");
  } finally {
    submitting.value = false;
  }
}

onShow(() => {
  loadTypes();
  loadRecords();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    loadRecords();
  }
});
</script>

<template>
  <view class="page">
    <view class="card">
      <view class="title">向管理员反馈</view>
      <view class="hint">功能建议、故障问题、账号或服务等事宜都可以写在这里。</view>
      <wd-picker v-model="model.type" label="反馈类型" :columns="typeColumns" />
      <wd-textarea v-model="model.description" placeholder="请描述你想反馈的内容" :maxlength="500" />
      <view class="images">
        <FallbackImage v-for="(src, index) in images" :key="src" class="shot" :src="src" mode="aspectFill" @click="removeImage(index)" />
        <view v-if="uploading" class="add uploading">{{ uploadLabel }}</view>
        <view v-else-if="images.length < 3" class="add" @click="chooseImage">+ 图片</view>
      </view>
      <wd-button type="primary" block :loading="submitting" :disabled="uploading" @click="handleSubmit">
        {{ uploading ? uploadLabel : "提交反馈" }}
      </wd-button>
    </view>

    <view class="section">我的反馈</view>
    <ListState
      :loading="loading"
      :error="error"
      :empty="records.length === 0"
      empty-text="暂无反馈记录"
      @retry="loadRecords"
    >
      <view class="list">
        <view v-for="item in records" :key="item.id" class="card">
          <view class="top">
            <text class="name">{{ item.typeLabel }}</text>
            <text class="status">{{ resultText[item.handleResult || ""] || statusText[item.status] || item.status }}</text>
          </view>
          <view v-if="item.description" class="desc">{{ item.description }}</view>
          <view v-if="item.handleRemark" class="desc">管理员：{{ item.handleRemark }}</view>
          <view class="time">{{ parseDateTime(item.handledAt || item.createdAt) }}</view>
        </view>
      </view>
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding-bottom: 24px;
}
.card {
  background: var(--kean-card);
  margin: 12px 16px;
  border-radius: 12px;
  padding: 14px 16px;
}
.title {
  font-weight: 600;
  color: var(--kean-text);
}
.hint {
  margin: 6px 0 12px;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.5;
}
.section {
  margin: 8px 16px 0;
  font-size: 13px;
  color: var(--kean-muted);
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.shot,
.add {
  width: 72px;
  height: 72px;
  border-radius: 8px;
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
.top {
  display: flex;
  justify-content: space-between;
}
.name {
  font-weight: 600;
}
.status {
  color: var(--kean-primary);
  font-size: 12px;
}
.desc,
.time {
  margin-top: 6px;
  color: var(--kean-muted);
  font-size: 12px;
}
.desc {
  color: var(--kean-sub);
}
</style>
