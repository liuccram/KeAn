import { listMyApplied, listMyPublished, type TaskItem } from "@/api/task";
import { useUserStore } from "@/store/user";
import { compareOngoing, isOngoingTask } from "@/utils/taskAction";
import { ref } from "vue";

export function useOngoingTasks() {
  const userStore = useUserStore();
  const items = ref<TaskItem[]>([]);
  const loading = ref(false);
  const error = ref("");

  async function load() {
    error.value = "";
    if (!userStore.isLoggedIn.value) {
      items.value = [];
      return;
    }
    loading.value = items.value.length === 0;
    try {
      const [published, applied] = await Promise.all([
        listMyPublished(1, 50, true),
        listMyApplied(1, 50, true)
      ]);
      const map = new Map<number, TaskItem>();
      [...(published.list || []), ...(applied.list || [])].forEach((task) => {
        if (isOngoingTask(task)) {
          map.set(task.id, task);
        }
      });
      items.value = [...map.values()].sort(compareOngoing);
    } catch (err) {
      // 失败必须留下信号：这里此前是静默「保持上次列表」，调用方
      // （首页 / 我的 / 进行中的代课）无从区分「确实没有进行中的代课」和「请求失败」。
      // 已有内容时保持上次列表；要不要轻提示由调用方决定，这里不弹 toast。
      error.value = (err as Error).message || "加载失败";
    } finally {
      loading.value = false;
    }
  }

  return { items, loading, error, load };
}
