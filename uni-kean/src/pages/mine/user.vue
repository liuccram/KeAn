<script setup lang="ts">
import { blockUser, listBlacklist, unblockUser } from "@/api/blacklist";
import { openChat } from "@/api/chat";
import { getPublicProfile, type PublicProfile } from "@/api/user";
import FallbackImage from "@/components/FallbackImage.vue";
import ListState from "@/components/ListState.vue";
import { formatRoleRating, genderLabel, parseDateTime, starText, trustRoleLabel } from "@/utils/format";
import { goReport } from "@/utils/report";
import { resolveMediaUrl } from "@/utils/request";
import { onLoad } from "@dcloudio/uni-app";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

const toast = useToast();
const loading = ref(false);
const error = ref("");
const chatting = ref(false);
const profile = ref<PublicProfile | null>(null);
const userId = ref(0);
/** 对方是否已在黑名单里（用 GET /api/blacklist 判定，见 loadBlocked） */
const blocked = ref(false);
/** 拉黑 / 移出请求在飞：只用来防重复点击与显示 loading，不参与任何判定 */
const blocking = ref(false);

const avatarText = computed(() => (profile.value?.nickname || "同").slice(0, 1));
const schoolLine = computed(() => {
  if (!profile.value) {
    return "";
  }
  if (profile.value.limited) {
    return profile.value.schoolName || "已设置隐私账号";
  }
  const school = profile.value.schoolName || "未设置学校";
  return profile.value.campusName ? `${school} · ${profile.value.campusName}` : school;
});

async function load() {
  if (!userId.value) {
    return;
  }
  // 本次请求前有没有资料：没有资料时加载失败交给 ListState 展示原因和「重新加载」，
  // 已有资料时只用轻提示，避免刷新失败把整页顶掉
  const first = !profile.value;
  if (first) {
    loading.value = true;
  }
  error.value = "";
  try {
    profile.value = await getPublicProfile(userId.value);
    uni.setNavigationBarTitle({ title: profile.value.nickname || "同学主页" });
    // 资料到手后顺带确认「在不在黑名单」：不 await、失败也不影响主页渲染
    void loadBlocked();
  } catch (err) {
    const message = (err as Error).message || "无法查看该主页";
    error.value = message;
    if (!first) {
      toast.error(message);
    }
  } finally {
    if (first) {
      loading.value = false;
    }
  }
}

/**
 * 对方是不是已经在我的黑名单里。
 *
 * <p>用**现有**接口 GET /api/blacklist 判断（黑名单页 pages/mine/blacklist.vue:23 用的是同一个
 * 函数、同一份数据源），不新增接口、不改数据层、不改未读/队列口径。</p>
 *
 * <p>⚠️ 为什么主页必须显示这个状态：会话页原来那个「加入黑名单」入口<b>没有任何状态判断</b>
 * （每次都直接弹确认再 POST），而后端对重复拉黑会抛错 ——
 * kean/src/main/java/com/kean/service/impl/BlacklistServiceImpl.java:90-95 查到已存在就
 * {@code throw new BizException(ErrorCode.ALREADY_BLOCKED)}。所以主页上如果仍然无条件显示
 * 「加入黑名单」，被拉黑的人第二次点就只会得到一个报错。这里把状态显示出来：
 * 已拉黑 → 按钮变「移出黑名单」（走同一份数据源 + 黑名单页同一套确认弹窗文案）。</p>
 *
 * <p>查不到（未登录 / 断网 / 接口异常）一律按「未拉黑」显示并静默：点下去若其实已拉黑，
 * 后端会返回 ALREADY_BLOCKED 的文案，走原来的错误提示路径，不会静默失败；
 * 反过来「把已拉黑显示成未拉黑」不改变任何数据，只是回到会话页原来的行为。</p>
 */
async function loadBlocked() {
  if (!userId.value || profile.value?.mine) {
    blocked.value = false;
    return;
  }
  try {
    const list = await listBlacklist();
    blocked.value = (list || []).some((row) => Number(row.blockedUserId) === Number(userId.value));
  } catch {
    blocked.value = false;
  }
}

/**
 * 加入黑名单 —— <b>逐字复用会话页原来那套逻辑</b>（chat.vue 迁移过来的 handleBlockPeer）：
 * 同一个确认弹窗标题与正文、同一个 blockUser(peerId)、成功后 toast.success("已加入黑名单")、
 * 400ms 后 uni.navigateBack()，失败提示也一致。唯一变化是取值来源：
 * peerId 从 session.peerUserId 换成主页的 userId、昵称从 session.peerNickname 换成 profile.nickname
 * （两者指向同一个人：会话点头像/消息列表点会话打开的就是 ?id=peerUserId 这个主页）。
 */
function handleBlock() {
  if (profile.value?.mine || !userId.value || blocking.value) {
    return;
  }
  const nickname = profile.value?.nickname || "对方";
  uni.showModal({
    title: "加入黑名单",
    content: `加入黑名单后，你将无法再与 ${nickname} 互发消息，确定继续？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      blocking.value = true;
      try {
        await blockUser(userId.value);
        blocked.value = true;
        toast.success("已加入黑名单");
        // 与会话页原行为一致：成功后短暂停留再返回上一页
        setTimeout(() => uni.navigateBack(), 400);
      } catch (error) {
        toast.error((error as Error).message || "加入黑名单失败");
      } finally {
        blocking.value = false;
      }
    }
  });
}

/**
 * 移出黑名单：会话页没有这个方向，用的是现有接口 unblockUser 与黑名单页
 * （pages/mine/blacklist.vue:38-55）同一套交互惯例与文案（「移出黑名单」/「确定将 X 移出黑名单？」/
 * 「已移出黑名单」）。移动方向相反，所以**不** navigateBack —— 用户是主动改主意，
 * 留在主页上继续看是应有行为，与「加入」后离开不同（那个行为按原样保留）。
 */
function handleUnblock() {
  if (profile.value?.mine || !userId.value || blocking.value) {
    return;
  }
  const nickname = profile.value?.nickname || "对方";
  uni.showModal({
    title: "移出黑名单",
    content: `确定将 ${nickname} 移出黑名单？`,
    success: async (res) => {
      if (!res.confirm) {
        return;
      }
      blocking.value = true;
      try {
        await unblockUser(userId.value);
        blocked.value = false;
        toast.success("已移出黑名单");
      } catch (error) {
        toast.error((error as Error).message || "操作失败");
      } finally {
        blocking.value = false;
      }
    }
  });
}

async function startChat() {
  if (!userId.value || chatting.value || profile.value?.mine) {
    return;
  }
  chatting.value = true;
  try {
    const session = await openChat(userId.value);
    uni.navigateTo({ url: `/pages/message/chat?id=${session.id}` });
  } catch (error) {
    toast.error((error as Error).message || "无法发起私信");
  } finally {
    chatting.value = false;
  }
}

function handleReport() {
  if (profile.value?.mine || !userId.value) {
    return;
  }
  goReport("USER", userId.value, profile.value?.nickname);
}

/**
 * 黑名单按钮的**唯一**入口：一个按钮两个方向（模板里不写行内三元判断，
 * 只绑定这一个函数，便于 grep 与小程序端编译）。
 */
function handleBlockToggle() {
  if (blocked.value) {
    handleUnblock();
    return;
  }
  handleBlock();
}

onLoad((query) => {
  userId.value = Number(query?.id || 0);
  load();
});
</script>

<template>
  <view class="page">
    <ListState
      :loading="loading"
      :error="error"
      :empty="!profile"
      empty-text="没有找到该用户"
      @retry="load"
    >
      <view v-if="profile" class="card">
        <view class="profile-kicker">CAMPUS PROFILE <text>课安互助社区</text></view>
        <view class="hero">
          <FallbackImage v-if="profile.avatarUrl" class="avatar" :src="resolveMediaUrl(profile.avatarUrl)" mode="aspectFill" />
          <view v-else class="avatar text">{{ avatarText }}</view>
          <view class="meta">
            <view class="name">
              {{ profile.nickname }}
              <text v-if="!profile.limited && profile.gender" class="tag">{{ genderLabel(profile.gender) }}</text>
            </view>
            <view class="school">{{ schoolLine }}</view>
          </view>
        </view>
        <view v-if="!profile.limited" class="stats">
          <view class="stat"><text class="stat-value">{{ formatRoleRating(profile.publishRatingAvg, profile.publishRatingCount) }}</text><text class="stat-label">发布评分 · {{ profile.publishCompletedCount || 0 }} 次完成</text></view>
          <view class="stat-divider" />
          <view class="stat"><text class="stat-value">{{ formatRoleRating(profile.applyRatingAvg, profile.applyRatingCount) }}</text><text class="stat-label">代课评分 · {{ profile.completedCount || 0 }} 次完成</text></view>
        </view>
        <view v-if="profile.limited" class="lock">该账号已设为隐私账号，仅展示有限信息</view>
        <!-- 操作区：会话页底部原来的「举报对方 / 举报消息 / 加入黑名单」操作条已整块删除，
             三个入口搬到这里 —— 两个举报**合并成一个**「举报该用户」（同一次 goReport("USER", id,
             昵称)，举报页里再选原因；「举报消息」那条需要具体消息 id 的能力随之取消），
             「加入黑名单」逐字复用会话页原来的确认弹窗 + blockUser + 提示 + 返回行为。
             ⚠️ 每个按钮独立成块、块间 12px 间距（.profile-actions 的 gap），**不是一条连体操作条**：
             原来三枚按钮在 .card 里是直接相邻的（wd-button 组件自带 margin-left/right: initial、
             没有任何上下外边距），视觉上会挤在一起。
             ⚠️ 这里只包了一层容器，**没有改动任何一枚 wd-button 上的属性/条件**：
             两个「发私信」仍是原来的 v-if / v-else-if 互斥分支，举报与拉黑仍各自带着
             自己的 v-if="!profile.mine"（容器上的 v-if 只是让"自己的主页"不渲染这层空壳，
             与原来「一个都不渲染」的结果逐字等价）。 -->
        <view v-if="!profile.mine" class="profile-actions">
          <wd-button v-if="!profile.mine && !profile.limited" type="primary" block :loading="chatting" @click="startChat">
            发私信
          </wd-button>
          <wd-button v-else-if="!profile.mine" type="primary" plain block :loading="chatting" @click="startChat">
            发私信
          </wd-button>
          <wd-button v-if="!profile.mine" plain block @click="handleReport">举报该用户</wd-button>
          <wd-button v-if="!profile.mine" plain block :loading="blocking" @click="handleBlockToggle">
            {{ blocked ? "移出黑名单" : "加入黑名单" }}
          </wd-button>
        </view>
      </view>

      <template v-if="profile && !profile.limited">
        <view class="block-title">收到的评价</view>
        <view v-if="profile.reviews?.length" class="reviews">
          <view v-for="item in profile.reviews" :key="item.id" class="review">
            <view class="review-top">
              <text class="from">{{ item.fromNickname || "同学" }}</text>
              <text class="stars">{{ starText(item.rating) }}</text>
            </view>
            <view class="course">{{ item.courseName }}<text v-if="trustRoleLabel(item.targetRole)"> · {{ trustRoleLabel(item.targetRole) }}</text></view>
            <view v-if="item.content" class="body">{{ item.content }}</view>
            <view class="time">{{ parseDateTime(item.createdAt) }}</view>
          </view>
        </view>
        <wd-status-tip v-else image="content" tip="暂无评价" />
      </template>
    </ListState>
    <wd-toast />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--kean-bg);
  padding: 12px 16px 24px;
}
.card {
  background: var(--kean-card);
  border:1px solid rgba(224,232,243,.9);
  border-radius: 20px;
  padding: 18px;
  box-shadow:0 8px 24px rgba(34,59,97,.05);
}
.profile-kicker { display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; color:#7795bb; font-size:9px; font-weight:700; letter-spacing:1px; }
.profile-kicker text { color:var(--kean-muted); font-size:10px; font-weight:400; letter-spacing:0; }
.hero {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 70px;
  height: 70px;
  border-radius: 20px;
  background: var(--kean-primary-soft);
  flex-shrink: 0;
}
.avatar.text {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--kean-primary-active);
  font-size: 24px;
  font-weight: 600;
}
.meta {
  min-width: 0;
}
.name {
  font-size: 18px;
  font-weight: 600;
  color: var(--kean-text);
}
.tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--kean-primary-active);
  background: #eef3ff;
  padding: 1px 6px;
  border-radius: 4px;
}
.school,
.rate {
  margin-top: 4px;
  color: var(--kean-muted);
  font-size: 13px;
}
.stats { display:flex; align-items:center; margin:16px 0; padding:14px 4px; border-top:1px solid var(--kean-line); border-bottom:1px solid var(--kean-line); }
.stat { display:flex; flex:1; flex-direction:column; gap:5px; min-width:0; }
.stat-value { color:var(--kean-text); font-size:15px; font-weight:700; }
.stat-label { color:var(--kean-muted); font-size:10px; }
.stat-divider { width:1px; height:32px; margin:0 12px; background:var(--kean-line); }
.lock {
  margin: 12px 0;
  padding: 10px 12px;
  background: #f7f8fa;
  color: var(--kean-muted);
  font-size: 13px;
  border-radius: 8px;
}
/* ---------------------------------------------------------------------------
 * 操作区（发私信 / 举报该用户 / 加入黑名单）
 * ---------------------------------------------------------------------------
 * ⚠️「按钮不要太紧靠」就落在这里：flex 纵向排列 + **12px** 间距。
 *    原状态是三枚 wd-button 在 .card 里直接相邻 —— 组件样式里只有
 *    `margin-left/right: initial`（node_modules/wot-design-uni/components/wd-button/index.scss:44-45），
 *    没有任何上下外边距，所以块与块之间是 0px，视觉上会挤在一起。
 *    用容器 gap 而不是给 .wd-button 加 margin：既不与异步分包里的组件 CSS 抢特异性
 *    （仓库铁律 4：wd-* 的样式永远排在入口 CSS 之后），也不用 :deep() 动组件内部。
 *    每个按钮仍是自己一块（is-block 撑满一行），读起来是**两个/三个独立可点项**，
 *    复用本页已有的 wd-button（primary / plain）与既有配色，不引入新的按钮体系。
 * ⚠️ 本规则只加 display / flex-direction / gap，**绝不加** backdrop-filter / transform /
 *    filter / will-change / contain / perspective：本页 <wd-toast> 的根节点带内联
 *    position:fixed（同类说明见 pages/mine/index.vue:280），一旦给它的祖先加上这些属性，
 *    就会把 fixed 后代劫持成「相对该容器定位」。不过 .profile-actions 在 .card 内部，
 *    <wd-toast /> 是它的**兄弟层**（.page 的直接子元素），本来就不是它的后代；
 *    这里仍然一条都不加，保持与全仓一致的约束。
 * --------------------------------------------------------------------------- */
.profile-actions {
  display: flex;
  flex-direction: column;
  /* 显式写 stretch：纵向 flex 的交叉轴是横向，让每枚 is-block 按钮都独占一行整宽
     （默认值 normal 在 flex 里等价于 stretch，这里写出来是为了不依赖那个等价关系） */
  align-items: stretch;
  gap: 12px;
}
.block-title {
  margin: 16px 4px 8px;
  color: var(--kean-muted);
  font-size: 13px;
}
.reviews {
  background: var(--kean-card);
  border:1px solid rgba(224,232,243,.9);
  border-radius: 16px;
  overflow: hidden;
  box-shadow:0 5px 16px rgba(34,59,97,.04);
}
.review {
  padding: 12px 16px;
  border-bottom: 1px solid var(--kean-line);
}
.review-top {
  display: flex;
  justify-content: space-between;
}
.from {
  font-weight: 600;
  color: var(--kean-text);
}
.stars {
  color: var(--kean-star);
  font-size: 12px;
}
.course {
  margin-top: 4px;
  color: var(--kean-muted);
  font-size: 12px;
}
.body {
  margin-top: 6px;
  color: var(--kean-sub);
  font-size: 14px;
  line-height: 1.5;
}
.time {
  margin-top: 6px;
  color: #c9cdd4;
  font-size: 11px;
}
</style>
