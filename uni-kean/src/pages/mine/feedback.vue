<script setup lang="ts">
import { createAppeal, listMyReports, listReportsAgainstMe, type ReportItem } from "@/api/report";
import { parseDateTime } from "@/utils/format";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useLiveUpdates } from "@/composables/useLiveUpdates";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

type TabKey = "records" | "results" | "against";

const toast = useToast();
const tab = ref<TabKey>("records");
const mine = ref<ReportItem[]>([]);
const againstMe = ref<ReportItem[]>([]);
const appealDrafts = ref<Record<number, string>>({});
const appealImageUrls = ref<Record<number, string[]>>({});
const appealImageKeys = ref<Record<number, string[]>>({});
const appealingId = ref<number | null>(null);

const records = computed(() => mine.value.filter((item) => item.status === "PENDING" || item.status === "PROCESSING"));
const results = computed(() => mine.value.filter((item) => item.status === "RESOLVED" || item.status === "REJECTED"));

const statusText: Record<string, string> = {
  PENDING: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已处理",
  REJECTED: "已驳回"
};

const resultText: Record<string, string> = {
  WARN: "警告",
  RESTRICT: "限制功能",
  BAN: "封禁账号",
  DELETE: "删除内容",
  REJECT: "驳回举报"
};

const appealStatusText: Record<string, string> = {
  PENDING: "待复核",
  ACCEPTED: "已受理",
  REJECTED: "未获支持"
};

const targetTypeText: Record<string, string> = {
  USER: "用户",
  TASK: "代课",
  MESSAGE: "消息"
};

onLoad((query) => {
  if (query?.targetType && query?.targetId) {
    let url = `/pages/mine/report?targetType=${encodeURIComponent(String(query.targetType))}&targetId=${query.targetId}`;
    if (query.targetLabel) {
      url += `&targetLabel=${encodeURIComponent(String(query.targetLabel))}`;
    }
    uni.redirectTo({ url });
  }
});

async function load() {
  try {
    const [reports, received] = await Promise.all([listMyReports(), listReportsAgainstMe()]);
    mine.value = (reports || []).filter((item) => item.targetType !== "FEEDBACK");
    againstMe.value = received || [];
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
}

function canAppeal(item: ReportItem) {
  return item.status === "RESOLVED" && !(item.appeals || []).length;
}

function chooseAppealImage(reportId: number) {
  const keys = appealImageKeys.value[reportId] || [];
  if (keys.length >= 3) {
    toast.info("最多上传 3 张图片");
    return;
  }
  uni.chooseImage({
    count: 3 - keys.length,
    sizeType: ["original"],
    sourceType: ["album", "camera"],
    success: async (res) => {
      for (const filePath of res.tempFilePaths || []) {
        try {
          const uploaded = await uploadFile(filePath, "APPEAL");
          appealImageKeys.value[reportId] = (appealImageKeys.value[reportId] || []).concat(uploaded.objectKey);
          appealImageUrls.value[reportId] = (appealImageUrls.value[reportId] || []).concat(
            resolveMediaUrl(uploaded.url || uploaded.objectKey)
          );
        } catch (error) {
          toast.error((error as Error).message || "图片上传失败");
        }
      }
    }
  });
}

function removeAppealImage(reportId: number, index: number) {
  appealImageKeys.value[reportId] = (appealImageKeys.value[reportId] || []).filter((_, i) => i !== index);
  appealImageUrls.value[reportId] = (appealImageUrls.value[reportId] || []).filter((_, i) => i !== index);
}

async function handleAppeal(item: ReportItem) {
  const content = (appealDrafts.value[item.id] || "").trim();
  const images = appealImageKeys.value[item.id] || [];
  if (!content && !images.length) {
    toast.error("请填写申诉说明或上传图片");
    return;
  }
  appealingId.value = item.id;
  try {
    await createAppeal(item.id, content, images);
    toast.success("申诉已提交，管理员将尽快复核");
    appealDrafts.value[item.id] = "";
    appealImageKeys.value[item.id] = [];
    appealImageUrls.value[item.id] = [];
    await load();
  } catch (error) {
    toast.error((error as Error).message || "申诉失败");
  } finally {
    appealingId.value = null;
  }
}

onShow(() => {
  load();
});

useLiveUpdates((event) => {
  if (!event || event.type === "NOTICE") {
    load();
  }
});
</script>

<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'records' }" @click="tab = 'records'">举报记录</view>
      <view class="tab" :class="{ on: tab === 'results' }" @click="tab = 'results'">处理结果</view>
      <view class="tab" :class="{ on: tab === 'against' }" @click="tab = 'against'">对我的处理</view>
    </view>

    <template v-if="tab === 'records'">
      <view v-if="records.length" class="list">
        <view v-for="item in records" :key="item.id" class="card">
          <view class="top">
            <text class="name">{{ item.targetLabel }}</text>
            <text class="status">{{ statusText[item.status] || item.status }}</text>
          </view>
          <view class="meta">{{ item.typeLabel }} · {{ targetTypeText[item.targetType] || item.targetType }}</view>
          <view v-if="item.description" class="desc">{{ item.description }}</view>
          <view class="time">{{ parseDateTime(item.createdAt) }}</view>
        </view>
      </view>
      <wd-status-tip v-else image="content" tip="暂无举报记录，可在代课、私聊或他人主页发起举报" />
    </template>

    <template v-else-if="tab === 'results'">
      <view v-if="results.length" class="list">
        <view v-for="item in results" :key="item.id" class="card">
          <view class="top">
            <text class="name">{{ item.targetLabel }}</text>
            <text class="status">{{ resultText[item.handleResult || ""] || statusText[item.status] }}</text>
          </view>
          <view class="meta">{{ item.typeLabel }} · {{ targetTypeText[item.targetType] || item.targetType }}</view>
          <view v-if="item.handleRemark" class="desc">处理说明：{{ item.handleRemark }}</view>
          <view class="time">{{ parseDateTime(item.handledAt || item.createdAt) }}</view>
        </view>
      </view>
      <wd-status-tip v-else image="content" tip="暂无处理结果" />
    </template>

    <template v-else>
      <view v-if="againstMe.length" class="list">
        <view v-for="item in againstMe" :key="'a-' + item.id" class="card">
          <view class="top">
            <text class="name">{{ item.reporterNickname || "同学" }}</text>
            <text class="status">{{ resultText[item.handleResult || ""] || statusText[item.status] }}</text>
          </view>
          <view class="meta">举报人 · {{ item.typeLabel }} · {{ parseDateTime(item.handledAt || item.createdAt) }}</view>
          <view v-if="item.handleRemark" class="desc">{{ item.handleRemark }}</view>
          <view v-for="appeal in item.appeals || []" :key="appeal.id" class="result">
            申诉{{ appealStatusText[appeal.status] || appeal.status }}：{{ appeal.content }}
            <view v-if="appeal.images?.length" class="images">
              <image v-for="src in appeal.images" :key="src" class="shot" :src="src" mode="aspectFill" />
            </view>
          </view>
          <view v-if="canAppeal(item)" class="appeal-box">
            <wd-textarea v-model="appealDrafts[item.id]" placeholder="对处理结果有异议，请说明理由" :maxlength="500" />
            <view class="images">
              <image
                v-for="(src, index) in appealImageUrls[item.id] || []"
                :key="src"
                class="shot"
                :src="src"
                mode="aspectFill"
                @click="removeAppealImage(item.id, index)"
              />
              <view v-if="(appealImageKeys[item.id] || []).length < 3" class="add" @click="chooseAppealImage(item.id)">+ 图片</view>
            </view>
            <wd-button size="small" type="primary" :loading="appealingId === item.id" @click="handleAppeal(item)">提交申诉</wd-button>
          </view>
        </view>
      </view>
      <wd-status-tip v-else image="content" tip="暂无针对你的处理" />
    </template>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 24px;
}
.tabs {
  display: flex;
  gap: 8px;
  margin: 10px 12px 0;
  padding: 4px;
  background: #eceff3;
  border-radius: 10px;
}
.tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  color: #86909c;
  font-size: 13px;
  border-radius: 8px;
}
.tab.on {
  color: #3d6fe8;
  font-weight: 600;
  background: #fff;
}
.list {
  padding-bottom: 8px;
}
.card {
  background: #fff;
  margin: 12px 16px;
  border-radius: 12px;
  padding: 14px 16px;
}
.top {
  display: flex;
  justify-content: space-between;
}
.name {
  font-weight: 600;
}
.status {
  color: #4d80f0;
  font-size: 12px;
}
.meta,
.desc,
.time,
.result {
  margin-top: 6px;
  color: #86909c;
  font-size: 12px;
}
.desc,
.result {
  color: #4e5969;
}
.appeal-box {
  margin-top: 10px;
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.shot,
.add {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  background: #f2f3f5;
}
.add {
  color: #86909c;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
