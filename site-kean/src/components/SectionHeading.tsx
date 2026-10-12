import type { ReactNode } from "react";
import styles from "./SectionHeading.module.css";

type Align = "left" | "center";

type Props = {
  index: string;
  label: string;
  title: string;
  body?: string;
  align?: Align;
  className?: string;
  children?: ReactNode;
};

export function SectionHeading({
  index,
  label,
  title,
  body,
  align = "left",
  className,
  children
}: Props) {
  return (
    <div className={`${styles.wrap} ${align === "center" ? styles.center : ""} ${className ?? ""}`}>
      <div className={styles.kicker}>
        <p className={styles.index} data-reveal="index">
          {index}
        </p>
        <p className={styles.label} data-reveal="label">
          {label}
        </p>
      </div>
      <h2 className={styles.title} data-reveal="title">
        {title}
      </h2>
      {body ? (
        <p className={styles.body} data-reveal="body">
          {body}
        </p>
      ) : null}
      {children ? (
        <div className={styles.extra} data-reveal="extra">
          {children}
        </div>
      ) : null}
    </div>
  );
}
