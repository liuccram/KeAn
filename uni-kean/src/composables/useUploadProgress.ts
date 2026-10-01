import { computed, ref } from "vue";
import type { UploadPhase, UploadProgress } from "@/utils/request";

/**
 * 上传进度的共享状态。
 *
 * 用法（注意：把 ref 解构到顶层，模板才会自动解包）：
 * ```ts
 * const { active: uploading, label: uploadLabel, onProgress, reset } = useUploadProgress();
 * await uploadFile(path, "REPORT", { onProgress });
 * ```
 */
export function useUploadProgress() {
  const active = ref(false);
  const phase = ref<UploadPhase>("uploading");
  const percent = ref(0);
  const attempt = ref(1);

  const label = computed(() => {
    if (!active.value) {
      return "";
    }
    if (phase.value === "preparing") {
      return "处理中…";
    }
    if (attempt.value > 1) {
      return `重试中 ${percent.value}%`;
    }
    return `上传中 ${percent.value}%`;
  });

  function onProgress(progress: UploadProgress) {
    active.value = true;
    phase.value = progress.phase;
    percent.value = progress.percent;
    attempt.value = progress.attempt;
  }

  function reset() {
    active.value = false;
    phase.value = "uploading";
    percent.value = 0;
    attempt.value = 1;
  }

  return { active, phase, percent, attempt, label, onProgress, reset };
}
