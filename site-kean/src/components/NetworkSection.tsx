import { useRef } from "react";
import { usePointerSpin } from "../hooks/usePointerSpin";
import { useSectionReveal, type RevealTimeline } from "../hooks/useSectionReveal";
import { NetworkEcosystem } from "../svg/NetworkEcosystem";
import { Starfield } from "../svg/Starfield";
import { SectionHeading } from "./SectionHeading";
import styles from "./NetworkSection.module.css";

function networkVisual(tl: RevealTimeline) {
  tl.to("[data-shard]", { opacity: 0.92, stagger: 0.025, duration: 0.42 }, 0.2)
    .to("[data-link]", { strokeDashoffset: 0, duration: 0.7, ease: "none" }, 0.26)
    .to("[data-node]", { opacity: 1, stagger: 0.02, duration: 0.4 }, 0.32)
    .to("[data-flow]", { opacity: 0.9, duration: 0.32 }, 0.5)
    .to("[data-campus]", { opacity: 1, duration: 0.4 }, 0.54);
}

export function NetworkSection() {
  const root = useRef<HTMLElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  usePointerSpin(stage);
  useSectionReveal(root, "parse", { visual: networkVisual });

  return (
    <section id="network" ref={root} className={styles.block}>
      <div ref={stage} className={styles.stage}>
        <Starfield seed={1} count={260} />
        <NetworkEcosystem />
      </div>
      <SectionHeading
        className={styles.heading}
        index="01"
        label="CAMPUS NETWORK"
        title="校园网络"
        body="连接课程、学生与代课机会。"
      />
    </section>
  );
}
