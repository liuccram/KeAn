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
  background: #fff;
  padding-bottom: 40px;
}
.header {
  padding: 20px 16px 12px;
  border-bottom: 1px solid #f2f3f5;
}
.title {
  font-size: 18px;
  font-weight: 700;
  color: #1d2129;
}
.date {
  margin-top: 6px;
  font-size: 12px;
  color: #86909c;
}
.body {
  padding: 8px 16px 0;
}
.h {
  margin: 20px 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: #1d2129;
}
.p {
  margin: 8px 0;
  font-size: 13px;
  line-height: 1.75;
  color: #4e5969;
}
.li {
  display: flex;
  margin: 6px 0;
}
.dot {
  color: #86909c;
  font-size: 13px;
  line-height: 1.75;
  padding-right: 6px;
}
.li-text {
  flex: 1;
  font-size: 13px;
  line-height: 1.75;
  color: #4e5969;
}
.foot {
  margin: 24px 16px 0;
  padding-top: 16px;
  border-top: 1px solid #f2f3f5;
  font-size: 12px;
  color: #86909c;
}
</style>
