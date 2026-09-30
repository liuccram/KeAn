import { listMyApplied, listMyPublished, type TaskItem } from "@/api/task";
import { useUserStore } from "@/store/user";
import { compareOngoing, isOngoingTask } from "@/utils/taskAction";
import { ref } from "vue";

export function useOngoingTasks() {
  const userStore = useUserStore();
  const items = ref<TaskItem[]>([]);
  const loading = ref(false);

  async function load() {
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
    } catch {
      // 保持上次列表
    } finally {
      loading.value = false;
    }
  }

  return { items, loading, load };
}
