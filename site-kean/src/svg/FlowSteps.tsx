import styles from "./FlowSteps.module.css";

const STEPS = [
  { n: "01", title: "发布", x: 56 },
  { n: "02", title: "申请", x: 180 },
  { n: "03", title: "确认", x: 304 }
];

export function FlowSteps() {
  return (
    <svg className={styles.svg} viewBox="0 0 360 168" role="img" aria-label="发布申请确认">
      <defs>
        <linearGradient id="flowLine" x1="56" y1="72" x2="304" y2="72" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="rgba(126, 182, 255, 0.2)" />
          <stop offset="50%" stopColor="rgba(201, 228, 255, 0.85)" />
          <stop offset="100%" stopColor="rgba(126, 182, 255, 0.35)" />
        </linearGradient>
      </defs>
      <line x1="56" y1="72" x2="304" y2="72" stroke="url(#flowLine)" strokeWidth="2" />
      {STEPS.map((step) => (
        <g key={step.n} data-flow-step="true" className={styles.step} transform={`translate(${step.x} 72)`}>
          <circle r="22" className={styles.halo} />
          <polygon points="0,-13 13,0 0,13 -13,0" className={styles.diamond} />
          <polygon points="0,-7 7,0 0,7 -7,0" className={styles.core} />
          <text className={styles.mark} y="-34">
            {step.n}
          </text>
          <text className={styles.title} y="48">
            {step.title}
          </text>
        </g>
      ))}
    </svg>
  );
}
