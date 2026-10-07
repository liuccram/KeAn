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
  <wd-form ref="formRef" :model="model" error-type="toast">
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
    <view class="footer">
      <wd-button v-if="!taskId" plain size="large" block :disabled="loading" @click="copyLast">填入上次发布</wd-button>
      <wd-button v-if="!taskId" plain size="large" block :disabled="loading" @click="clearForm">清空</wd-button>
      <wd-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
        {{ taskId ? "保存修改" : "发布代课" }}
      </wd-button>
    </view>
  </wd-form>
</template>

<style scoped>
.footer {
  padding: 24px 16px 40px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
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
