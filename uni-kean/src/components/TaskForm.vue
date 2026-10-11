<script setup lang="ts">
import { createTask, getTask, listMyPublished, updateTask, type TaskItem, type TaskPayload } from "@/api/task";
import CampusHighlight from "@/components/CampusHighlight.vue";
import { formatDate, locationLockReason, tomorrowAt } from "@/utils/format";
import { useToast } from "wot-design-uni";
import { computed, onMounted, reactive, ref } from "vue";

const props = defineProps<{ taskId?: number }>();
const emit = defineEmits<{ success: [id: number]; reset: [] }>();

const toast = useToast();
const formRef = ref();
const loading = ref(false);
const lockCore = ref(false);
const lockLocation = ref(false);

const model = reactive({
  courseName: "",
  campusText: "",
  taskDate: tomorrowAt(8, 0),
  startTime: "08:00",
  endTime: "09:40",
  building: "",
  classroom: "",
  computerLab: "0",
  requirePhoto: "0",
  genderRequirement: "ANY",
  reward: "0",
  reason: "",
  requirement: "",
  remark: ""
});

const minDate = Date.now();
// 校区可选、由用户手输文本：填了就用统一的红色高亮标签显示一遍（让该校同学一眼看到），没填不显示
const selectedCampusName = computed(() => model.campusText.trim());
const computerLabColumns = [
  { label: "否", value: "0" },
  { label: "是", value: "1" }
];
const genderRequirementColumns = [
  { label: "不限", value: "ANY" },
  { label: "仅限男生", value: "MALE" },
  { label: "仅限女生", value: "FEMALE" }
];

const gratitudeOptions = [0, 5, 10, 20];

function toggleOption(field: "computerLab" | "requirePhoto", value: string) {
  if (field === "computerLab" && lockLocation.value) return;
  if (field === "requirePhoto" && lockCore.value) return;
  model[field] = model[field] === value ? (value === "1" ? "0" : "1") : value;
}

function selectReward(value: number) {
  model.reward = String(value);
}

function fillFromTask(task: TaskItem) {
  model.courseName = task.courseName;
  // 校区是手输文本：回填服务端算好的展示名（新数据是文本，旧数据是 campus_id 关联出的旧校区名）
  model.campusText = task.campusName || "";
  model.taskDate = new Date(`${task.taskDate}T00:00:00`).getTime();
  model.startTime = task.startTime;
  model.endTime = task.endTime;
  model.building = task.building;
  model.classroom = task.classroom;
  model.computerLab = task.computerLab === 1 ? "1" : "0";
  model.requirePhoto = task.requirePhoto === 1 ? "1" : "0";
  model.genderRequirement = task.genderRequirement || "ANY";
  model.reward = String(task.reward ?? 0);
  model.reason = task.reason || "";
  model.requirement = task.requirement || "";
  model.remark = task.remark || "";
}

async function loadDetail() {
  if (!props.taskId) {
    return;
  }
  const task = await getTask(props.taskId);
  lockCore.value = !(task.status === "WAITING" && (task.applyCount || 0) === 0);
  lockLocation.value = Boolean(locationLockReason(task));
  fillFromTask(task);
}

async function copyLast() {
  const data = await listMyPublished(1, 1);
  const last = data.list[0];
  if (!last) {
    toast.info("还没有发布过代课");
    return;
  }
  fillFromTask(last);
  toast.success("已填入上次发布内容");
}

/**
 * 发布页会保留上次填写的内容（切 Tab / 返回不再清空），因此需要一个明确的清空入口。
 * 清空交给外层重建组件：初始值只在下面 model 的声明处定义一份，不会漏字段。
 */
function clearForm() {
  uni.showModal({
    title: "清空已填内容？",
    content: "表单里填写的内容会被全部清空。",
    confirmText: "清空",
    success: (res) => {
      if (res.confirm) {
        emit("reset");
      }
    }
  });
}

function buildPayload(): TaskPayload {
  return {
    courseName: model.courseName.trim(),
    campusText: model.campusText.trim() || null,
    taskDate: formatDate(Number(model.taskDate)),
    startTime: String(model.startTime).slice(0, 5),
    endTime: String(model.endTime).slice(0, 5),
    building: model.building.trim(),
    classroom: model.classroom.trim(),
    computerLab: String(model.computerLab) === "1",
    requirePhoto: String(model.requirePhoto) === "1",
    genderRequirement: String(model.genderRequirement),
    reward: Number(model.reward || 0),
    reason: model.reason.trim() || undefined,
    requirement: model.requirement.trim() || undefined,
    remark: model.remark.trim() || undefined
  };
}

function toMinutes(value: string | number) {
  const text = String(value || "").slice(0, 5);
  const [hour, minute] = text.split(":").map((part) => Number(part));
  if (Number.isNaN(hour) || Number.isNaN(minute)) {
    return 0;
  }
  return hour * 60 + minute;
}

function addMinutes(value: string, extra: number) {
  const total = Math.min(23 * 60 + 59, toMinutes(value) + extra);
  const hour = String(Math.floor(total / 60)).padStart(2, "0");
  const minute = String(total % 60).padStart(2, "0");
  return `${hour}:${minute}`;
}

function onStartConfirm() {
  if (toMinutes(model.endTime) <= toMinutes(model.startTime)) {
    model.endTime = addMinutes(String(model.startTime).slice(0, 5), 40);
    toast.info("下课时间不得早于上课时间，已自动后移");
  }
}

function handleSubmit() {
  // 先把必填文本框按 payload 的口径去掉首尾空格：表单的 required 规则判定的是原值，
  // 而 buildPayload() 发出去的是 trim 后的值 —— 纯空格能通过前端校验，却会被服务端
  // @NotBlank 拒绝（400 / 40000，且服务端不打日志）。统一口径后前端会先给出「请填写…」。
  model.courseName = model.courseName.trim();
  model.building = model.building.trim();
  model.classroom = model.classroom.trim();
  formRef.value
    .validate()
    .then(async ({ valid }: { valid: boolean }) => {
      if (!valid) {
        return;
      }
      if (toMinutes(model.endTime) <= toMinutes(model.startTime)) {
        toast.error("下课时间不得早于上课时间");
        return;
      }
      // reward 在 payload 里是 Number(model.reward || 0)：非数字会得到 NaN，
      // JSON.stringify 会把它写成 null，服务端 @NotNull 直接判 400（也不打日志）；
      // 负数则会撞 @DecimalMin("0.00")。这里提前拦掉，给出明确提示。
      const reward = Number(model.reward || 0);
      if (!Number.isFinite(reward) || reward < 0) {
        toast.error("酬谢金额请填写不小于 0 的数字");
        return;
      }
      loading.value = true;
      try {
        const payload = buildPayload();
        const saved = props.taskId ? await updateTask(props.taskId, payload) : await createTask(payload);
        if (props.taskId) {
          toast.success("已保存");
          emit("success", saved.id);
        } else {
          uni.showToast({ title: "发布成功", icon: "success", duration: 1500 });
          setTimeout(() => {
            emit("success", saved.id);
          }, 800);
        }
      } catch (error) {
        toast.error((error as Error).message || "提交失败");
      } finally {
        loading.value = false;
      }
    })
    .catch(() => undefined);
}

onMounted(async () => {
  try {
    await loadDetail();
  } catch (error) {
    toast.error((error as Error).message || "加载失败");
  }
});
</script>

<template>
  <view v-if="!taskId" class="publish-shell">
    <view class="publish-heading">
      <view class="heading-kicker"><view class="kicker-dot" /> CAMPUS HELP</view>
      <text class="heading-title">需要同学帮你代课？</text>
      <text class="heading-subtitle">把这节课交给一位靠谱的同学，约 30 秒发布</text>
      <view class="progress-row"><view class="progress-track"><view class="progress-fill" /></view><text>完善需求 · 1 分钟内</text></view>
    </view>

    <wd-form ref="formRef" :model="model" error-type="toast" class="request-form">
      <view class="request-card course-card">
        <view class="section-heading"><view class="section-icon course-icon">书</view><view><text class="section-title">哪门课需要帮忙？</text><text class="section-caption">先告诉大家课程名称</text></view><text class="step-number">01</text></view>
        <wd-input v-model="model.courseName" prop="courseName" clearable placeholder="例如：高等数学（上）" :disabled="lockCore" :rules="[{ required: true, message: '请填写课程名' }]" />
      </view>

      <view class="request-card">
        <view class="section-heading"><view class="section-icon time-icon">时</view><view><text class="section-title">什么时候需要帮助？</text><text class="section-caption">选好日期和上课时段</text></view><text class="step-number">02</text></view>
        <wd-datetime-picker v-model="model.taskDate" type="date" label="上课日期" prop="taskDate" :min-date="minDate" :disabled="lockCore" :rules="[{ required: true, message: '请选择上课日期' }]" />
        <view class="time-line"><view class="time-point" /><view class="time-line-track" /><view class="time-point end-point" /></view>
        <view class="time-pickers">
          <view class="time-picker"><text class="time-label">开始</text><wd-datetime-picker v-model="model.startTime" type="time" prop="startTime" :disabled="lockCore" :rules="[{ required: true, message: '请选择开始时间' }]" @confirm="onStartConfirm" /></view>
          <text class="time-dash">—</text>
          <view class="time-picker"><text class="time-label">下课</text><wd-datetime-picker v-model="model.endTime" type="time" prop="endTime" :disabled="lockCore" :rules="[{ required: true, message: '请选择结束时间' }, { validator: (value: string) => toMinutes(value) > toMinutes(model.startTime), message: '下课时间不得早于上课时间' } ]" /></view>
        </view>
      </view>

      <view class="request-card">
        <view class="section-heading"><view class="section-icon place-icon">地</view><view><text class="section-title">上课地点</text><text class="section-caption">让接单的同学快速找到教室</text></view><text class="step-number">03</text></view>
        <wd-input v-model="model.campusText" label="校区" prop="campusText" clearable maxlength="50" placeholder="选填，例如 西校区" :disabled="lockLocation" />
        <view v-if="selectedCampusName" class="campus-tip"><text class="campus-tip-label">本单校区：</text><CampusHighlight :name="selectedCampusName" /></view>
        <view class="location-fields"><wd-input v-model="model.building" label="教学楼" prop="building" clearable placeholder="例如 教学楼 A" :disabled="lockLocation" :rules="[{ required: true, message: '请填写教学楼' }]" /><wd-input v-model="model.classroom" label="教室" prop="classroom" clearable placeholder="例如 101" :disabled="lockLocation" :rules="[{ required: true, message: '请填写教室' }]" /></view>
      </view>

      <view class="request-card">
        <view class="section-heading"><view class="section-icon help-icon">帮</view><view><text class="section-title">希望对方帮你做什么？</text><text class="section-caption">点选需要的协助，信息更清楚</text></view><text class="step-number">04</text></view>
        <view class="option-block"><text class="option-label">上机操作</text><view class="choice-row"><view class="choice-chip" :class="{ selected: model.computerLab === '0' }" @click="toggleOption('computerLab', '0')">无需上机</view><view class="choice-chip" :class="{ selected: model.computerLab === '1' }" @click="toggleOption('computerLab', '1')">需要上机</view></view><wd-picker v-model="model.computerLab" class="validation-picker" label="上机要求" prop="computerLab" :columns="computerLabColumns" :disabled="lockLocation" :rules="[{ required: true, message: '请选择是否上机' }]" /></view>
        <view class="option-block"><text class="option-label">课堂反馈</text><view class="choice-row"><view class="choice-chip" :class="{ selected: model.requirePhoto === '0' }" @click="toggleOption('requirePhoto', '0')">无需拍照</view><view class="choice-chip" :class="{ selected: model.requirePhoto === '1' }" @click="toggleOption('requirePhoto', '1')">拍照反馈</view></view><wd-picker v-model="model.requirePhoto" class="validation-picker" label="拍照要求" prop="requirePhoto" :columns="computerLabColumns" :disabled="lockCore" :rules="[{ required: true, message: '请选择是否拍照' }]" /></view>
        <text class="helper-note">选择拍照反馈后，代课同学需在课堂期间上传现场照片。</text>
        <wd-picker v-model="model.genderRequirement" label="性别要求" prop="genderRequirement" :columns="genderRequirementColumns" :disabled="lockCore" :rules="[{ required: true, message: '请选择性别要求' }]" />
      </view>

      <view class="request-card reward-card">
        <view class="section-heading"><view class="section-icon reward-icon">谢</view><view><text class="section-title">愿意提供多少感谢？</text><text class="section-caption">酬谢由你决定，也可以之后私下结算</text></view><text class="step-number">05</text></view>
        <view class="reward-options"><view v-for="amount in gratitudeOptions" :key="amount" class="reward-chip" :class="{ selected: model.reward === String(amount) }" @click="selectReward(amount)">{{ amount === 0 ? '随心意' : `${amount} 元` }}</view><view class="reward-custom"><wd-input v-model="model.reward" prop="reward" type="digit" placeholder="自定义金额" :rules="[{ required: true, message: '请填写酬谢金额' }]" /><text>元</text></view></view>
        <view class="thanks-note"><text class="thanks-spark">✦</text><text>一句感谢，也能让校园互助更温暖</text></view>
      </view>

      <view class="request-card details-card">
        <view class="section-heading"><view class="section-icon note-icon">补</view><view><text class="section-title">再补充一点说明</text><text class="section-caption">选填，让需求更容易被理解</text></view></view>
        <wd-textarea v-model="model.reason" label="代课原因" label-width="72px" placeholder="例如：临时有事无法到课" maxlength="500" />
        <wd-textarea v-model="model.requirement" label="具体要求" label-width="72px" placeholder="希望对方帮忙完成什么？" maxlength="500" />
        <wd-textarea v-model="model.remark" label="其他备注" label-width="72px" placeholder="补充信息（选填）" maxlength="500" />
      </view>

      <view v-if="lockCore || lockLocation" class="edit-lock-note">{{ lockLocation ? '已有同学接下代课，开课前 1 小时内不能修改地点。' : '已有申请后，课程和课堂要求不可修改。' }}</view>
      <view class="footer publish-footer">
        <view class="secondary-actions"><wd-button plain size="large" :disabled="loading" @click="copyLast">填入上次发布</wd-button><wd-button plain size="large" :disabled="loading" @click="clearForm">清空</wd-button></view>
        <wd-button type="primary" size="large" block :loading="loading" @click="handleSubmit">发布这条求助</wd-button>
        <text class="privacy-note">发布后，校内同学就能看到你的代课需求</text>
      </view>
    </wd-form>
  </view>

  <view v-else class="edit-shell">
  <view class="publish-heading edit-heading">
    <view class="heading-kicker"><view class="kicker-dot" /> TASK UPDATE</view>
    <text class="heading-title">调整这条求助</text>
    <text class="heading-subtitle">更新课程安排与说明，保存后会同步给相关同学</text>
  </view>
  <wd-form ref="formRef" :model="model" error-type="toast">
    <view class="request-card edit-card">
    <view class="section-heading"><view class="section-icon course-icon">改</view><view><text class="section-title">求助信息</text><text class="section-caption">部分内容可能因申请状态而锁定</text></view></view>
    <wd-cell-group border>
      <wd-input
        v-model="model.courseName"
        label="课程名"
        label-width="80px"
        prop="courseName"
        clearable
        placeholder="请输入课程名称"
        :disabled="lockCore"
        :rules="[{ required: true, message: '请填写课程名' }]"
      />
      <wd-datetime-picker
        v-model="model.taskDate"
        type="date"
        label="上课日期"
        label-width="80px"
        prop="taskDate"
        :min-date="minDate"
        :disabled="lockCore"
        :rules="[{ required: true, message: '请选择上课日期' }]"
      />
      <wd-datetime-picker
        v-model="model.startTime"
        type="time"
        label="开始时间"
        label-width="80px"
        prop="startTime"
        :disabled="lockCore"
        :rules="[{ required: true, message: '请选择开始时间' }]"
        @confirm="onStartConfirm"
      />
      <wd-datetime-picker
        v-model="model.endTime"
        type="time"
        label="结束时间"
        label-width="80px"
        prop="endTime"
        :disabled="lockCore"
        :rules="[
          { required: true, message: '请选择结束时间' },
          {
            validator: (value: string) => toMinutes(value) > toMinutes(model.startTime),
            message: '下课时间不得早于上课时间'
          }
        ]"
      />
      <wd-input
        v-model="model.campusText"
        label="校区"
        label-width="80px"
        prop="campusText"
        clearable
        :maxlength="50"
        placeholder="选填，例如 西校区"
        :disabled="lockLocation"
      />
      <view v-if="selectedCampusName" class="campus-tip">
        <text class="campus-tip-label">本单校区：</text><CampusHighlight :name="selectedCampusName" />
      </view>
      <wd-input
        v-model="model.building"
        label="教学楼"
        label-width="80px"
        prop="building"
        clearable
        placeholder="例如 教学楼A"
        :disabled="lockLocation"
        :rules="[{ required: true, message: '请填写教学楼' }]"
      />
      <wd-input
        v-model="model.classroom"
        label="教室"
        label-width="80px"
        prop="classroom"
        clearable
        placeholder="例如 101"
        :disabled="lockLocation"
        :rules="[{ required: true, message: '请填写教室' }]"
      />
      <wd-picker
        v-model="model.computerLab"
        label="是否上机"
        label-width="80px"
        prop="computerLab"
        :columns="computerLabColumns"
        :disabled="lockLocation"
        :rules="[{ required: true, message: '请选择是否上机' }]"
      />
      <wd-picker
        v-model="model.requirePhoto"
        label="是否拍照"
        label-width="80px"
        prop="requirePhoto"
        :columns="computerLabColumns"
        :disabled="lockCore"
        :rules="[{ required: true, message: '请选择是否拍照' }]"
      />
      <view class="hint">选择“是” 则代课者须在开课前 5 分钟至下课前上传现场照片；选择“否” 则下课后可直接确认完成。</view>
      <wd-picker
        v-model="model.genderRequirement"
        label="性别要求"
        label-width="80px"
        prop="genderRequirement"
        :columns="genderRequirementColumns"
        :disabled="lockCore"
        :rules="[{ required: true, message: '请选择性别要求' }]"
      />
      <wd-input
        v-model="model.reward"
        label="酬谢"
        label-width="80px"
        prop="reward"
        type="digit"
        placeholder="仅展示，允许 0"
        :rules="[{ required: true, message: '请填写酬谢金额' }]"
      />
      <view class="hint">可私信自行结算{{ lockLocation ? "已有人接代课，开课前 1 小时内不能修改地点。" : lockCore ? "已有申请后只能改地点、备注和酬谢，是否拍照不可再改。" : "" }}</view>
      <wd-textarea v-model="model.reason" label="代课原因" label-width="80px" placeholder="选填" :maxlength="500" />
      <wd-textarea v-model="model.requirement" label="代课要求" label-width="80px" placeholder="选填" :maxlength="500" />
      <wd-textarea v-model="model.remark" label="备注" label-width="80px" placeholder="选填" :maxlength="500" />
    </wd-cell-group>
    </view>
    <view class="footer edit-footer">
      <wd-button v-if="!taskId" plain size="large" block :disabled="loading" @click="copyLast">填入上次发布</wd-button>
      <wd-button v-if="!taskId" plain size="large" block :disabled="loading" @click="clearForm">清空</wd-button>
      <wd-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
        {{ taskId ? "保存修改" : "发布代课" }}
      </wd-button>
    </view>
  </wd-form>
  </view>
</template>

<style scoped>
.footer {
  padding: 24px 16px 40px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.publish-shell { padding: 24px 16px 20px; }
.edit-shell { padding: 20px 16px 0; }
.edit-heading { padding: 12px 4px 16px; }
.edit-card { padding-top:17px; }
.edit-footer { padding:5px 0 36px; }
.publish-heading { padding: 12px 4px 22px; }
.heading-kicker { display: flex; align-items: center; gap: 7px; margin-bottom: 12px; color: #6384bd; font-size: 10px; font-weight: 700; letter-spacing: 1.5px; }
.kicker-dot { width: 7px; height: 7px; border-radius: 50%; background: #71a8f4; box-shadow: 0 0 0 4px rgba(113,168,244,.14); }
.heading-title { display: block; color: var(--kean-title, #172944); font-size: 25px; line-height: 1.35; font-weight: 750; letter-spacing: -.6px; }
.heading-subtitle { display: block; margin-top: 7px; color: var(--kean-muted, #8592a5); font-size: 13px; line-height: 1.6; }
.progress-row { display: flex; align-items: center; gap: 9px; margin-top: 18px; color: #8592a5; font-size: 11px; }
.progress-track { width: 54px; height: 4px; border-radius: 9px; background: #e9eef7; overflow: hidden; }
.progress-fill { width: 36%; height: 100%; border-radius: inherit; background: #6e9ce7; }
.request-form { display: block; }
.request-card { margin-bottom: 14px; padding: 17px 15px 15px; border: 1px solid rgba(224,232,243,.9); border-radius: 18px; background: rgba(255,255,255,.94); box-shadow: 0 8px 24px rgba(37,65,108,.045); overflow: hidden; }
.course-card { border-color: rgba(155,187,234,.5); background: linear-gradient(145deg, rgba(255,255,255,.98), rgba(242,247,255,.94)); }
.section-heading { display: flex; align-items: center; gap: 11px; margin-bottom: 12px; }
.section-icon { width: 34px; height: 34px; display: flex; align-items: center; justify-content: center; border-radius: 11px; font-size: 12px; font-weight: 700; }
.course-icon { color: #4c79bf; background: #eaf2ff; }.time-icon { color: #478a8b; background: #e8f6f2; }.place-icon { color: #c47e52; background: #fff1e6; }.help-icon { color: #7768b8; background: #f0edff; }.reward-icon { color: #be8a34; background: #fff5dc; }.note-icon { color: #537f94; background: #eaf5fa; }
.section-title { display: block; color: #263853; font-size: 14px; font-weight: 700; }
.section-caption { display: block; margin-top: 3px; color: #94a0b1; font-size: 11px; }
.step-number { margin-left: auto; color: #c1cad7; font-size: 11px; letter-spacing: 1px; }
.request-card :deep(.wd-cell-group) { border-radius: 12px; overflow: hidden; }
.request-card :deep(.wd-cell-group__title) { display: none; }
.request-card :deep(.wd-cell) { padding-left: 10px; padding-right: 10px; background: rgba(248,250,253,.72); }
.course-card :deep(.wd-input) { padding: 3px 10px; border-radius: 12px; background: rgba(255,255,255,.85); }
.time-line { display: flex; align-items: center; padding: 13px 14px 0; }
.time-point { width: 9px; height: 9px; border: 2px solid #76a8e9; border-radius: 50%; background: #fff; z-index: 1; }.time-point.end-point { border-color: #73b9a7; }
.time-line-track { flex: 1; height: 2px; margin: 0 -1px; background: linear-gradient(90deg,#a8c7f1,#b4dfd4); }
.time-pickers { display: flex; align-items: center; justify-content: space-between; padding: 0 4px; }
.time-picker { width: 44%; }.time-label { display: block; padding: 0 10px 4px; color: #95a1b1; font-size: 11px; }.time-picker :deep(.wd-cell) { min-height: 42px; padding-top: 8px; padding-bottom: 8px; border-radius: 11px; background: #f7f9fc; }.time-dash { padding-top: 17px; color: #b1bdcc; }
.location-fields { display: flex; gap: 8px; margin-top: 8px; }.location-fields > * { min-width: 0; flex: 1; }
.campus-tip { padding: 8px 12px 2px; font-size: 12px; }.campus-tip-label { color: var(--kean-sub); }
.option-block { position: relative; padding: 10px 0 4px; }.option-label { display: block; margin-bottom: 8px; color: #7c899b; font-size: 12px; }
.choice-row { display: flex; gap: 9px; }.choice-chip,.reward-chip { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 14px; border: 1px solid #e7ebf2; border-radius: 11px; color: #748195; background: #fafbfd; font-size: 12px; }.choice-chip.selected,.reward-chip.selected { border-color: #a8c4ef; color: #4774b7; background: #eef5ff; box-shadow: inset 0 0 0 1px rgba(115,157,220,.1); }
.validation-picker { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; overflow: hidden; }
.helper-note { display: block; margin: 7px 0 10px; color: #9aa5b4; font-size: 10px; line-height: 1.5; }
.reward-options { display: flex; flex-wrap: wrap; gap: 8px; }.reward-chip { min-width: 60px; min-height: 38px; }.reward-custom { display: flex; align-items: center; gap: 3px; min-width: 116px; padding-right: 10px; border: 1px solid #e7ebf2; border-radius: 11px; color: #8793a4; background: #fafbfd; font-size: 12px; }.reward-custom :deep(.wd-input) { flex: 1; min-width: 0; padding: 0 0 0 10px; background: transparent; }.reward-custom :deep(.wd-input__label),.reward-custom :deep(.wd-input__clear) { display: none; }.reward-custom :deep(input) { height: 36px; font-size: 12px; }
.thanks-note { display: flex; align-items: center; gap: 6px; margin-top: 13px; color: #a58a54; font-size: 10px; }.thanks-spark { color: #e2b95c; }
.details-card :deep(.wd-textarea) { margin-top: 5px; border-radius: 11px; background: #f8fafd; }.details-card :deep(.wd-textarea__label) { color: #748195; font-size: 12px; }
.edit-lock-note { padding: 4px 4px 12px; color: #9a7d56; font-size: 11px; line-height: 1.5; }
.publish-footer { padding: 5px 0 20px; gap: 10px; }.secondary-actions { display: flex; gap: 10px; }.secondary-actions :deep(.wd-button) { flex: 1; margin: 0; border-color: #dfe6f0; color: #748195; background: rgba(255,255,255,.8); }.publish-footer > :deep(.wd-button--primary) { min-height: 48px; border-radius: 14px; background: linear-gradient(110deg,#5d8dd7,#719cde); box-shadow: 0 7px 16px rgba(86,133,203,.2); font-weight: 650; }.privacy-note { display: block; text-align: center; color: #a0aab8; font-size: 10px; }
.hint {
  padding: 8px 16px 0;
  color: var(--kean-muted);
  font-size: 12px;
  line-height: 1.6;
}
.campus-tip {
  padding: 8px 16px 0;
  font-size: 12px;
}
.campus-tip-label {
  color: var(--kean-sub);
}
</style>
