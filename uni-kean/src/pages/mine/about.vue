<script setup lang="ts">
const VERSION = "0.1.0";

const features: { title: string; desc: string }[] = [
  { title: "发布代课", desc: "填好日期、时间段、校区、教室与酬谢，等同学来申请" },
  { title: "申请接单", desc: "看到合适的时间段直接申请，也可随时撤回" },
  { title: "择优匹配", desc: "发布者从申请人里挑一位，一单一接，其余申请自动关闭" },
  { title: "到场与完成", desc: "可要求代课者上传现场照片；下课后任一方确认即完成" },
  { title: "双向评价", desc: "星级 + 标签 + 文字，发布可信度与代课可信度分开计算" },
  { title: "私信沟通", desc: "匹配后可以私信确认教室与见面地点" },
  { title: "举报与申诉", desc: "遇到问题可举报，对处理结果有异议还可申诉" },
  { title: "隐私与安全", desc: "隐私账号、黑名单、登录设备管理，可远程下线其他设备" }
];
</script>

<template>
  <view class="page">
    <view class="hero">
      <image class="logo" src="/static/logo.png" mode="aspectFit" />
      <view class="name">课安</view>
      <view class="version">v{{ VERSION }}</view>
      <view class="slogan">校园临时代课互助平台</view>
    </view>

    <wd-cell-group border title="能做什么">
      <wd-cell v-for="item in features" :key="item.title" :title="item.title" :label="item.desc" />
    </wd-cell-group>

    <view class="tip">酬谢仅作展示，由双方自行结算，平台不经手资金。</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 32px;
}
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 36px 16px 28px;
  background: #fff;
}
.logo {
  width: 72px;
  height: 72px;
}
.name {
  margin-top: 12px;
  font-size: 20px;
  font-weight: 700;
  color: #1d2129;
}
.version {
  margin-top: 4px;
  color: #86909c;
  font-size: 12px;
}
.slogan {
  margin-top: 8px;
  color: #4e5969;
  font-size: 14px;
}
.tip {
  padding: 16px;
  color: #86909c;
  font-size: 12px;
  line-height: 1.6;
}
</style>
