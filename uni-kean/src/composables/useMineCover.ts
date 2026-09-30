import { updateCover } from "@/api/auth";
import defaultCover from "@/static/mine-hero-bg.png";
import { chooseAndCrop } from "@/utils/imageCrop";
import { useUserStore } from "@/store/user";
import { resolveMediaUrl, uploadFile } from "@/utils/request";
import { useToast } from "wot-design-uni";
import { computed, ref } from "vue";

export function useMineCover() {
  const toast = useToast();
  const userStore = useUserStore();
  const uploading = ref(false);

  const isCustom = computed(() => Boolean(userStore.state.user?.coverUrl));
  const heroSrc = computed(() => resolveMediaUrl(userStore.state.user?.coverUrl) || defaultCover);

  function applyUser(user: typeof userStore.state.user) {
    if (userStore.state.token && user) {
      userStore.setLogin(userStore.state.token, user);
    }
  }

  async function chooseCover() {
    if (uploading.value) {
      return;
    }
    const filePath = await chooseAndCrop("cover");
    if (!filePath) {
      return;
    }
    uploading.value = true;
    try {
      const uploaded = await uploadFile(filePath, "COVER");
      const latest = await updateCover(uploaded.objectKey);
      applyUser(latest);
      toast.success("背景图已更新");
    } catch (error) {
      toast.error((error as Error).message || "背景图上传失败");
    } finally {
      uploading.value = false;
    }
  }

  async function resetCover() {
    if (uploading.value) {
      return;
    }
    uploading.value = true;
    try {
      const latest = await updateCover("");
      applyUser(latest);
      toast.success("已恢复默认背景");
    } catch (error) {
      toast.error((error as Error).message || "恢复失败");
    } finally {
      uploading.value = false;
    }
  }

  function openCoverSheet() {
    const items = isCustom.value ? ["从相册选择", "恢复默认"] : ["从相册选择"];
    uni.showActionSheet({
      itemList: items,
      success: (res) => {
        if (res.tapIndex === 0) {
          chooseCover();
          return;
        }
        if (res.tapIndex === 1) {
          resetCover();
        }
      }
    });
  }

  return {
    uploading,
    isCustom,
    heroSrc,
    chooseCover,
    resetCover,
    openCoverSheet
  };
}
