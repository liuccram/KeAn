import styles from "./MatchConstellation.module.css";

const STARS = [
  { x: 48, y: 92, r: 2.4, hot: false },
  { x: 92, y: 48, r: 3.2, hot: true },
  { x: 148, y: 78, r: 2.6, hot: false },
  { x: 186, y: 36, r: 3.8, hot: true },
  { x: 236, y: 70, r: 2.8, hot: false },
  { x: 288, y: 44, r: 3.1, hot: false },
  { x: 318, y: 96, r: 2.5, hot: false },
  { x: 64, y: 156, r: 2.8, hot: false },
  { x: 118, y: 138, r: 4.2, hot: true },
  { x: 168, y: 168, r: 3, hot: false },
  { x: 214, y: 128, r: 5, hot: true },
  { x: 268, y: 158, r: 3.4, hot: true },
  { x: 312, y: 178, r: 2.6, hot: false },
  { x: 86, y: 214, r: 2.4, hot: false },
  { x: 154, y: 228, r: 3.2, hot: false },
  { x: 222, y: 210, r: 2.8, hot: false },
  { x: 286, y: 226, r: 3.6, hot: true }
];

const LINKS: [number, number][] = [
  [1, 2],
  [2, 3],
  [3, 4],
  [8, 10],
  [10, 11],
  [8, 9],
  [10, 16]
];

export function MatchConstellation() {
  return (
    <svg className={styles.svg} viewBox="0 0 360 260" role="img" aria-label="快速匹配星座">
      <defs>
        <radialGradient id="matchGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="rgba(61, 111, 232, 0.28)" />
          <stop offset="100%" stopColor="rgba(61, 111, 232, 0)" />
        </radialGradient>
      </defs>
      <ellipse cx="210" cy="138" rx="120" ry="88" fill="url(#matchGlow)" />
      {LINKS.map(([a, b]) => (
        <line
          key={`${a}-${b}`}
          x1={STARS[a].x}
          y1={STARS[a].y}
          x2={STARS[b].x}
          y2={STARS[b].y}
          className={styles.link}
        />
      ))}
      {STARS.map((star) => (
        <g key={`${star.x}-${star.y}`}>
          {star.hot ? <circle cx={star.x} cy={star.y} r={star.r + 8} className={styles.halo} /> : null}
          <circle cx={star.x} cy={star.y} r={star.r} className={star.hot ? styles.hot : styles.dot} />
        </g>
      ))}
    </svg>
  );
}
