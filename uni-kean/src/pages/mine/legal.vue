<script setup lang="ts">
import { privacyDoc } from "@/utils/legal";

// 本页只渲染隐私政策（用户协议暂不放进 App）
const doc = privacyDoc;
</script>

<template>
  <view class="page">
    <view class="header">
      <view class="title">{{ doc.title }}</view>
      <view v-if="doc.effectiveDate" class="date">生效日期：{{ doc.effectiveDate }}</view>
    </view>

    <view class="body">
      <template v-for="(block, index) in doc.blocks" :key="index">
        <view v-if="block.type === 'h'" class="h">{{ block.text }}</view>
        <view v-else-if="block.type === 'li'" class="li">
          <text class="dot">·</text>
          <text class="li-text">{{ block.text }}</text>
        </view>
        <view v-else class="p">{{ block.text }}</view>
      </template>
    </view>

    <view class="foot">如对以上内容有疑问，可通过邮件与我们联系。</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f9fc;
  padding-bottom: 40px;
}
.header {
  margin:0 16px;
  padding: 20px 4px 14px;
  border-bottom: 1px solid var(--kean-line);
}
.title {
  font-size: 18px;
  font-weight: 700;
  color: var(--kean-text);
}
.date {
  margin-top: 6px;
  font-size: 12px;
  color: var(--kean-muted);
}
.body {
  margin:0 16px;
  padding: 8px 4px 0;
}
.h {
  margin: 24px 0 9px;
  padding-left:10px;
  border-left:3px solid #9eb8dc;
  font-size: 15px;
  font-weight: 600;
  color: var(--kean-text);
}
.p {
  margin: 9px 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--kean-sub);
}
.li {
  display: flex;
  margin: 6px 0;
}
.dot {
  color: var(--kean-muted);
  font-size: 13px;
  line-height: 1.75;
  padding-right: 6px;
}
.li-text {
  flex: 1;
  font-size: 13px;
  line-height: 1.9;
  color: var(--kean-sub);
}
.foot {
  margin: 24px 20px 0;
  padding: 16px 12px 0;
  border-top: 1px solid var(--kean-line);
  font-size: 12px;
  color: var(--kean-muted);
}
</style>
