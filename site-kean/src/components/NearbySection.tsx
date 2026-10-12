import { lazy, Suspense, useEffect, useRef } from "react";
import { nearbyProgress } from "../lib/progress";
import type { GpuTier } from "../hooks/useGpuTier";
import { useInView } from "../hooks/useInView";
import { useMediaQuery } from "../hooks/useMediaQuery";
import { useSectionReveal, type RevealTimeline } from "../hooks/useSectionReveal";
import { Starfield } from "../svg/Starfield";
import { RadarField } from "../svg/RadarField";
import { SectionHeading } from "./SectionHeading";
import styles from "./NearbySection.module.css";

const loadNearbyScene = () =>
  import("../three/NearbyScene").then((mod) => ({ default: mod.NearbyScene }));

const NearbyScene = lazy(loadNearbyScene);

type Props = {
  tier: GpuTier;
};

function nearbyVisual(tl: RevealTimeline) {
  tl.to(nearbyProgress, { value: 1, duration: 0.82, ease: "none" }, 0.16);
}

export function NearbySection({ tier }: Props) {
  const root = useRef<HTMLElement>(null);
  const mobile = useMediaQuery("(max-width: 768px)");
  const ready = useInView(root, "55% 0px", true);
  useSectionReveal(root, "approach", { visual: nearbyVisual });

  useEffect(() => {
    if (mobile) {
      return;
    }
    const idle = window.requestIdleCallback?.bind(window);
    if (idle) {
      const id = idle(() => {
        void loadNearbyScene();
      }, { timeout: 700 });
      return () => window.cancelIdleCallback?.(id);
    }
    const timer = window.setTimeout(() => {
      void loadNearbyScene();
    }, 400);
    return () => window.clearTimeout(timer);
  }, [mobile]);

  return (
    <section id="nearby" ref={root} className={styles.block}>
      <div className={styles.stage}>
        <Starfield seed={0} count={240} />
        {mobile ? (
          <RadarField />
        ) : tier !== "low" && ready ? (
          <Suspense fallback={null}>
            <NearbyScene tier={tier} />
          </Suspense>
        ) : null}
      </div>
      <SectionHeading
        className={styles.heading}
        index="02"
        label="NEARBY"
        title="附近课程"
        body="发现身边正在发生的课程机会。"
        align="center"
      />
    </section>
  );
}
