import styles from "./RadarField.module.css";

const NODES = [
  { x: 92, y: 78, r: 4, glow: true },
  { x: 268, y: 86, r: 3.2, glow: false },
  { x: 58, y: 168, r: 3.6, glow: false },
  { x: 302, y: 156, r: 4.4, glow: true },
  { x: 118, y: 236, r: 3, glow: false },
  { x: 248, y: 228, r: 3.8, glow: true },
  { x: 180, y: 54, r: 2.8, glow: false }
];

export function RadarField() {
  return (
    <svg className={styles.svg} viewBox="0 0 360 300" role="img" aria-label="附近课程雷达">
      <defs>
        <radialGradient id="radarGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="rgba(61, 111, 232, 0.35)" />
          <stop offset="100%" stopColor="rgba(61, 111, 232, 0)" />
        </radialGradient>
      </defs>
      <ellipse cx="180" cy="150" rx="148" ry="148" fill="url(#radarGlow)" />
      {[44, 80, 116, 148].map((r) => (
        <circle key={r} cx="180" cy="150" r={r} className={styles.ring} />
      ))}
      <line x1="180" y1="4" x2="180" y2="296" className={styles.axis} />
      <line x1="32" y1="150" x2="328" y2="150" className={styles.axis} />
      <path d="M180 150 L268 80" className={styles.sweep} />
      {NODES.map((node) => (
        <g key={`${node.x}-${node.y}`}>
          {node.glow ? <circle cx={node.x} cy={node.y} r={node.r + 7} className={styles.halo} /> : null}
          <circle cx={node.x} cy={node.y} r={node.r} className={node.glow ? styles.dotHot : styles.dot} />
        </g>
      ))}
      <circle cx="180" cy="150" r="16" className={styles.coreRing} />
      <circle cx="180" cy="150" r="6" className={styles.core} />
    </svg>
  );
}
