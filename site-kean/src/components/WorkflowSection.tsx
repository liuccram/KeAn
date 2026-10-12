import { useRef } from "react";
import { TimeTracks } from "../svg/TimeTracks";
import { FlowSteps } from "../svg/FlowSteps";
import { Starfield } from "../svg/Starfield";
import { usePointerSpin } from "../hooks/usePointerSpin";
import { useMediaQuery } from "../hooks/useMediaQuery";
import { useSectionReveal, type RevealTimeline } from "../hooks/useSectionReveal";
import { SectionHeading } from "./SectionHeading";
import styles from "./WorkflowSection.module.css";

function flowVisual(tl: RevealTimeline) {
  tl.to("[data-rail]", { strokeDashoffset: 0, duration: 0.7, stagger: 0.04, ease: "none" }, 0.06)
    .to("[data-flow-step]", { opacity: 1, stagger: 0.1, duration: 0.28 }, 0.14)
    .to("[data-step='01']", { opacity: 1, duration: 0.28 }, 0.14)
    .to("[data-time]", { opacity: 1, duration: 0.28 }, 0.18)
    .to("[data-step='02']", { opacity: 1, duration: 0.28 }, 0.34)
    .to("[data-event]", { opacity: 1, duration: 0.3 }, 0.38)
    .to("[data-tie]", { opacity: 0.45, duration: 0.28 }, 0.42)
    .to("[data-step='03']", { opacity: 1, duration: 0.28 }, 0.52)
    .to("[data-flow]", { opacity: 0.85, duration: 0.3 }, 0.48)
    .to("[data-campus]", { opacity: 1, duration: 0.36 }, 0.56);
}

export function WorkflowSection() {
  const root = useRef<HTMLElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const mobile = useMediaQuery("(max-width: 768px)");
  usePointerSpin(stage, 8);
  useSectionReveal(root, "flow", { visual: flowVisual });

  return (
    <section id="flow" ref={root} className={styles.block}>
      <div ref={stage} className={styles.stage}>
        <Starfield seed={2} count={240} />
        {mobile ? <FlowSteps /> : <TimeTracks />}
      </div>
      <SectionHeading
        className={styles.heading}
        index="03"
        label="COURSE FLOW"
        title="发布 · 申请 · 确认"
        body="一次代课，三步对齐。"
      />
    </section>
  );
}
