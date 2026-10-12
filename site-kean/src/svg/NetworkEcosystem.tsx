import { useEffect, useRef } from "react";
import gsap from "gsap";
import { CampusSilhouette } from "./CampusSilhouette";
import { useMediaQuery } from "../hooks/useMediaQuery";
import styles from "./NetworkEcosystem.module.css";

const SHARDS = [
  { x: 430, y: 140, r: 14, s: 0.7 },
  { x: 520, y: 520, r: -22, s: 0.78 },
  { x: 610, y: 240, r: 8, s: 0.55 },
  { x: 680, y: 620, r: 18, s: 0.62 },
  { x: 740, y: 330, r: -26, s: 0.74 },
  { x: 860, y: 430, r: 10, s: 1 },
  { x: 940, y: 160, r: 6, s: 0.58 },
  { x: 980, y: 250, r: -16, s: 1.15 },
  { x: 940, y: 620, r: -12, s: 0.64 },
  { x: 1060, y: 540, r: 18, s: 0.92 },
  { x: 1140, y: 180, r: 8, s: 0.52 },
  { x: 1210, y: 310, r: 28, s: 0.82 },
  { x: 1280, y: 620, r: -8, s: 0.7 },
  { x: 1320, y: 470, r: -36, s: 0.7 },
  { x: 1420, y: 280, r: 22, s: 0.58 },
  { x: 1480, y: 540, r: 16, s: 0.5 }
];

type Node = {
  x: number;
  y: number;
  s: number;
  kind: "d" | "t" | "h";
  label?: string;
};

const NODES: Node[] = [
  { x: 480, y: 200, s: 5, kind: "t" },
  { x: 560, y: 460, s: 4, kind: "d" },
  { x: 640, y: 320, s: 6, kind: "h" },
  { x: 700, y: 500, s: 5, kind: "t" },
  { x: 760, y: 380, s: 5, kind: "d" },
  { x: 790, y: 240, s: 8, kind: "d", label: "课程" },
  { x: 820, y: 620, s: 4, kind: "h" },
  { x: 880, y: 160, s: 4, kind: "h" },
  { x: 960, y: 480, s: 6, kind: "t" },
  { x: 1010, y: 390, s: 9, kind: "h" },
  { x: 1080, y: 280, s: 5, kind: "d" },
  { x: 1180, y: 560, s: 6, kind: "d" },
  { x: 1240, y: 200, s: 7, kind: "t", label: "同学" },
  { x: 1290, y: 150, s: 4, kind: "t" },
  { x: 1360, y: 390, s: 6, kind: "d", label: "代课" },
  { x: 1410, y: 600, s: 4, kind: "d" },
  { x: 1480, y: 470, s: 5, kind: "t" },
  { x: 1520, y: 260, s: 4, kind: "h" }
];

const LINKS: [number, number][] = [
  [0, 2],
  [0, 5],
  [1, 3],
  [2, 4],
  [2, 5],
  [3, 6],
  [4, 5],
  [4, 8],
  [5, 7],
  [5, 10],
  [7, 10],
  [7, 12],
  [8, 9],
  [8, 11],
  [9, 10],
  [9, 14],
  [10, 12],
  [11, 14],
  [11, 15],
  [12, 13],
  [12, 14],
  [14, 16],
  [14, 17],
  [16, 17]
];

function diamond(x: number, y: number, s: number) {
  return `${x},${y - s} ${x + s},${y} ${x},${y + s} ${x - s},${y}`;
}

function triangle(x: number, y: number, s: number) {
  return `${x},${y - s} ${x + s * 0.95},${y + s * 0.7} ${x - s * 0.95},${y + s * 0.7}`;
}

function hex(x: number, y: number, s: number) {
  const pts: string[] = [];
  for (let i = 0; i < 6; i += 1) {
    const a = (Math.PI / 3) * i - Math.PI / 6;
    pts.push(`${x + Math.cos(a) * s},${y + Math.sin(a) * s}`);
  }
  return pts.join(" ");
}

function shape(node: Node) {
  if (node.kind === "t") {
    return triangle(0, 0, node.s);
  }
  if (node.kind === "h") {
    return hex(0, 0, node.s);
  }
  return diamond(0, 0, node.s);
}

function curve(a: Node, b: Node) {
  const mx = (a.x + b.x) / 2;
  const my = (a.y + b.y) / 2 + (a.x < b.x ? -48 : 42);
  return `M${a.x} ${a.y} Q${mx} ${my} ${b.x} ${b.y}`;
}

const DUST = Array.from({ length: 64 }, (_, i) => {
  const n = Math.sin(i * 12.9898) * 43758.5453;
  const u = n - Math.floor(n);
  const m = Math.sin(i * 78.233) * 23421.631;
  const v = m - Math.floor(m);
  return { x: 360 + u * 1200, y: 60 + v * 780, s: 1.2 + u * 1.8 };
});

const FIELD = Array.from({ length: 96 }, (_, i) => {
  const col = i % 12;
  const row = Math.floor(i / 12);
  const jitter = ((i * 17) % 9) - 4;
  return { x: 430 + col * 92 + jitter, y: 88 + row * 86 + (col % 2 === 0 ? 18 : 0) };
});

export function NetworkEcosystem() {
  const root = useRef<SVGSVGElement>(null);
  const compact = useMediaQuery("(max-width: 768px)");

  useEffect(() => {
    const svg = root.current;
    if (!svg) {
      return;
    }
    const ctx = gsap.context(() => {
      gsap.set("[data-shard]", { opacity: 0.16 });
      gsap.set("[data-link]", { strokeDashoffset: 280 });
      gsap.set("[data-node]", { opacity: 0.2 });
      gsap.set("[data-campus]", { opacity: 0 });
      gsap.set("[data-flow]", { opacity: 0 });
    }, svg);
    return () => ctx.revert();
  }, []);

  return (
    <svg
      ref={root}
      className={styles.svg}
      viewBox={compact ? "500 40 1020 900" : "0 0 1600 900"}
      preserveAspectRatio="xMidYMid slice"
      role="img"
      aria-label="课安校园网络"
    >
      <ellipse cx="1080" cy="420" rx="560" ry="360" fill="rgba(61, 111, 232, 0.16)" />
      <ellipse cx="860" cy="360" rx="280" ry="180" fill="rgba(126, 182, 255, 0.08)" />
      <ellipse cx="520" cy="560" rx="320" ry="210" fill="rgba(12, 32, 64, 0.38)" />
      <ellipse cx="1260" cy="180" rx="220" ry="140" fill="rgba(61, 111, 232, 0.1)" />
      <g data-campus="true" transform="translate(40 612) scale(1.92)">
        <CampusSilhouette opacity={0.7} />
      </g>
      {FIELD.map((dot) => (
        <polygon
          key={`f-${dot.x}-${dot.y}`}
          className={styles.field}
          points={`${dot.x},${dot.y - 2.2} ${dot.x + 2.2},${dot.y} ${dot.x},${dot.y + 2.2} ${dot.x - 2.2},${dot.y}`}
        />
      ))}
      {DUST.map((dot) => (
        <polygon
          key={`${dot.x}-${dot.y}`}
          className={styles.dust}
          points={`${dot.x},${dot.y - dot.s} ${dot.x + dot.s},${dot.y} ${dot.x},${dot.y + dot.s} ${dot.x - dot.s},${dot.y}`}
        />
      ))}
      <text className={styles.aux} x="1188" y="108">
        CAMPUS NETWORK
      </text>
      {LINKS.map(([i, j], index) => (
        <path
          key={`l-${index}`}
          data-link="true"
          d={curve(NODES[i], NODES[j])}
          className={styles.link}
          strokeDasharray="280"
        />
      ))}
      {LINKS.filter((_, i) => i % 2 === 0).map(([i, j], index) => (
        <path
          key={`f-${index}`}
          data-flow="true"
          d={curve(NODES[i], NODES[j])}
          className={styles.flow}
        />
      ))}
      {SHARDS.map((shard, index) => (
        <g
          key={`s-${index}`}
          data-shard="true"
          transform={`translate(${shard.x} ${shard.y}) rotate(${shard.r}) scale(${shard.s})`}
        >
          <polygon
            points="0,-34 30,18 -30,18"
            fill="rgba(126, 182, 255, 0.05)"
            stroke="rgba(159, 208, 255, 0.42)"
            strokeWidth="1"
          />
          <polygon
            points="0,-18 16,10 -16,10"
            fill="none"
            stroke="rgba(201, 228, 255, 0.28)"
            strokeWidth="0.8"
          />
        </g>
      ))}
      {NODES.map((node) => (
        <g key={`${node.x}-${node.y}`} data-node="true" transform={`translate(${node.x} ${node.y})`}>
          <polygon
            points={shape({ ...node, x: 0, y: 0 })}
            fill="rgba(12, 20, 36, 0.65)"
            stroke="#9fd0ff"
            strokeWidth="1.1"
          />
          <circle r={node.s * 0.32} fill="#eaf4ff" opacity={node.label ? 0.95 : 0.42} />
          {node.label ? (
            <text className={styles.label} x="0" y={-node.s - 10}>
              {node.label}
            </text>
          ) : null}
        </g>
      ))}
    </svg>
  );
}
