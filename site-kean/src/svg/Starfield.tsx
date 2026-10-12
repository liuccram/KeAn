import { useMemo } from "react";
import { useMediaQuery } from "../hooks/useMediaQuery";
import styles from "./Starfield.module.css";

type Star = {
  x: number;
  y: number;
  r: number;
  o: number;
  twinkle: boolean;
};

function frac(n: number) {
  return n - Math.floor(n);
}

function makeStars(count: number, seed: number): Star[] {
  const stars: Star[] = [];
  for (let i = 0; i < count; i += 1) {
    const u = frac(Math.sin(i * 12.9898 + seed) * 43758.5453);
    const v = frac(Math.sin(i * 78.233 + seed * 1.7) * 23421.631);
    const w = frac(Math.sin(i * 39.17 + seed * 2.3) * 9123.12);
    stars.push({
      x: u * 1280,
      y: v * 720,
      r: 0.7 + w * 2.2,
      o: 0.34 + w * 0.56,
      twinkle: i % 3 === 0
    });
  }
  return stars;
}

type Props = {
  seed?: number;
  count?: number;
  layer?: "local" | "fixed";
};

export function Starfield({ seed = 1, count = 220, layer = "local" }: Props) {
  const compact = useMediaQuery("(max-width: 768px)");
  const total = compact ? Math.min(count, 168) : count;
  const stars = useMemo(() => makeStars(total, seed), [total, seed]);
  return (
    <div className={`${styles.wrap} ${layer === "fixed" ? styles.fixed : ""}`} aria-hidden="true">
      <svg className={styles.svg} viewBox="0 0 1280 720" preserveAspectRatio="xMidYMid slice">
        <g className={styles.drift}>
          {stars.map((star, i) => (
            <circle
              key={`${star.x}-${i}`}
              className={star.twinkle ? styles.twinkle : undefined}
              cx={star.x}
              cy={star.y}
              r={star.r}
              fill="#e4f1ff"
              opacity={star.o}
              style={star.twinkle ? { animationDelay: `${(i % 7) * 0.28}s` } : undefined}
            />
          ))}
        </g>
      </svg>
    </div>
  );
}
