import { useRef } from "react";
import { matchingProgress } from "../lib/progress";
import type { GpuTier } from "../hooks/useGpuTier";
import { usePointerSpin } from "../hooks/usePointerSpin";
import { useMediaQuery } from "../hooks/useMediaQuery";
import { useSectionReveal, type RevealTimeline } from "../hooks/useSectionReveal";
import { ScanMatch } from "../svg/ScanMatch";
import { MatchConstellation } from "../svg/MatchConstellation";
import { Starfield } from "../svg/Starfield";
import { SectionHeading } from "./SectionHeading";
import styles from "./MatchingSection.module.css";

type Props = {
  tier: GpuTier;
};

function matchingVisual(tl: RevealTimeline) {
  tl.to(matchingProgress, { value: 1, duration: 0.92, ease: "none" }, 0.06).to(
    "[data-matched]",
    { autoAlpha: 0.92, duration: 0.24 },
    0.58
  );
}

export function MatchingSection({ tier }: Props) {
  const root = useRef<HTMLElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const mobile = useMediaQuery("(max-width: 768px)");
  usePointerSpin(stage, 8);
  useSectionReveal(root, "scan", { visual: matchingVisual });

  return (
    <section id="match" ref={root} className={styles.block}>
      <div ref={stage} className={styles.stage}>
        <Starfield seed={3} count={240} />
        {mobile ? <MatchConstellation /> : <ScanMatch dense={tier === "high"} />}
      </div>
      <SectionHeading
        className={styles.heading}
        index="04"
        label="MATCHING"
        title="快速匹配"
        body="从许多可能，到一次确认。"
      />
      <p className={styles.word} data-matched="">
        MATCHED
      </p>
    </section>
  );
}
