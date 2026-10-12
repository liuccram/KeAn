import { useRef } from "react";
import { useSectionReveal } from "../hooks/useSectionReveal";
import { SectionHeading } from "./SectionHeading";
import styles from "./DownloadSection.module.css";

export function DownloadSection() {
  const root = useRef<HTMLElement>(null);
  useSectionReveal(root, "quiet");

  return (
    <section id="download" ref={root} className={styles.block}>
      <SectionHeading index="05" label="DOWNLOAD" title="现在开始使用课安" body="Android 8.0+ · APK" align="center">
        <a className="cta" href="/downloads/kean.apk">
          下载 Android APK
        </a>
      </SectionHeading>
    </section>
  );
}
