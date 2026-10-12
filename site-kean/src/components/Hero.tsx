import { lazy, Suspense, type MouseEvent } from "react";
import type { GpuTier } from "../hooks/useGpuTier";
import styles from "./Hero.module.css";

const DigitalCoreScene = lazy(() =>
  import("../three/DigitalCoreScene").then((mod) => ({ default: mod.DigitalCoreScene }))
);

type Props = {
  tier: GpuTier;
};

export function Hero({ tier }: Props) {
  const onDownload = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    document.getElementById("download")?.scrollIntoView({ behavior: "smooth" });
  };

  return (
    <section id="top" className={styles.hero}>
      <div className={styles.stage} aria-hidden="true">
        {tier === "low" ? (
          <div className={styles.fallback} />
        ) : (
          <Suspense fallback={<div className={styles.fallback} />}>
            <DigitalCoreScene tier={tier} />
          </Suspense>
        )}
        <svg className={styles.skyline} viewBox="0 0 375 78" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
          <g fill="none" stroke="rgba(168, 208, 255, 0.22)" strokeWidth="1">
            <path d="M0 78 V52 H18 V78" />
            <path d="M0 52 L9 40 L18 52" />
            <rect x="22" y="58" width="16" height="20" />
            <rect x="42" y="44" width="14" height="34" />
            <path d="M60 78 V38 H84 V78" />
            <path d="M60 38 L72 26 L84 38" />
            <rect x="88" y="50" width="22" height="28" />
            <rect x="114" y="42" width="16" height="36" />
            <path d="M134 78 V30 H166 V78" />
            <rect x="170" y="56" width="28" height="22" />
            <path d="M202 78 V36 H226 V78" />
            <path d="M202 36 L214 24 L226 36" />
            <rect x="230" y="48" width="14" height="30" />
            <rect x="248" y="34" width="24" height="44" />
            <path d="M276 78 V40 H308 V78" />
            <rect x="312" y="52" width="20" height="26" />
            <path d="M336 78 V44 H375 V78" />
          </g>
        </svg>
      </div>
      <div className={styles.copy}>
        <p className={styles.brand} data-intro>
          课安
        </p>
        <h1 data-intro>让校园代课更简单</h1>
        <p className={styles.lead} data-intro>
          连接有课要替、有空能上的同学。
        </p>
        <a className="cta" href="#download" onClick={onDownload} data-intro>
          下载 Android APK
        </a>
      </div>
    </section>
  );
}
