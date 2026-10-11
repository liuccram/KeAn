<script setup lang="ts">
import { createReport, listMyReports, listReportTypes, type ReportItem, type ReportTypeItem } from "@/api/report";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import PageIntro from "@/components/PageIntro.vue";
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
  <view class="page advice-page">
    <PageIntro eyebrow="WE LISTEN" title="反馈与建议" description="你的想法会帮助课安变得更好" marker="反馈" />
    <view class="card compose-card">
      <view class="compose-heading"><view class="compose-mark">01</view><view><view class="title">向管理员反馈</view><view class="hint">功能建议、故障问题或账号服务问题</view></view></view>
      <wd-picker v-model="model.type" label="反馈类型" :columns="typeColumns" />
      <view class="field-caption">说说你遇到的情况</view><wd-textarea v-model="model.description" placeholder="尽量描述发生了什么、你希望怎么改进" :maxlength="500" />
      <view class="images">
        <FallbackImage v-for="(src, index) in images" :key="src" class="shot" :src="src" mode="aspectFill" @click="removeImage(index)" />
        <view v-if="uploading" class="add uploading">{{ uploadLabel }}</view>
        <view v-else-if="images.length < 3" class="add" @click="chooseImage">+ 图片</view>
      </view>
      <wd-button type="primary" block :loading="submitting" :disabled="uploading" @click="handleSubmit">
        {{ uploading ? uploadLabel : "提交反馈" }}
      </wd-button>
    </view>

    <view class="section-heading"><view class="section">我的反馈</view><text class="section-note">提交后可在这里查看进展</text></view>
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
  border: 1px solid rgba(224,232,243,.9);
  border-radius: 17px;
  padding: 16px;
  box-shadow: 0 5px 16px rgba(34,59,97,.04);
}
.compose-heading { display:flex; align-items:center; gap:10px; margin-bottom:12px; }.compose-mark { width:32px; height:32px; display:flex; align-items:center; justify-content:center; border-radius:10px; color:#5a81b8; background:#edf4ff; font-size:10px; font-weight:700; }.field-caption { margin:14px 0 7px; color:var(--kean-sub); font-size:12px; font-weight:600; }.section-heading { display:flex; align-items:baseline; justify-content:space-between; margin:12px 16px 0; }.section-note { color:var(--kean-muted); font-size:10px; }
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
