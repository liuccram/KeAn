import { useEffect, useMemo, useRef } from "react";
import { matchingProgress } from "../lib/progress";
import styles from "./ScanMatch.module.css";

type Node = {
  x: number;
  y: number;
  s: number;
  hit: boolean;
  pair?: boolean;
};

function seed(i: number) {
  const x = Math.sin(i * 127.1 + 11.7) * 43758.5453;
  return x - Math.floor(x);
}

function makeNodes(count: number): Node[] {
  const nodes: Node[] = [];
  for (let i = 0; i < count; i += 1) {
    nodes.push({
      x: 160 + seed(i) * 1120,
      y: 120 + seed(i + 9) * 520,
      s: 3.2 + seed(i + 3) * 4.2,
      hit: seed(i + 21) > 0.78
    });
  }
  const right = nodes.filter((n) => n.x > 780);
  if (right[3]) {
    right[3].hit = true;
    right[3].pair = true;
  }
  if (right[7]) {
    right[7].hit = true;
    right[7].pair = true;
  }
  return nodes;
}

export function ScanMatch({ dense }: { dense: boolean }) {
  const root = useRef<SVGSVGElement>(null);
  const scan = useRef<SVGLineElement>(null);
  const nodes = useMemo(() => makeNodes(dense ? 96 : 58), [dense]);
  const pair = nodes.filter((n) => n.pair);

  useEffect(() => {
    const svg = root.current;
    const line = scan.current;
    if (!svg || !line) {
      return;
    }
    const marks = svg.querySelectorAll<SVGPolygonElement>("[data-cell]");
    const link = svg.querySelector<SVGLineElement>("[data-confirm]");
    let frame = 0;
    const tick = () => {
      const p = matchingProgress.value;
      const x = 140 + p * 1160;
      line.setAttribute("x1", String(x));
      line.setAttribute("x2", String(x));
      marks.forEach((mark, i) => {
        const node = nodes[i];
        if (!node) {
          return;
        }
        const passed = x > node.x - 8;
        const lit = passed && node.hit;
        const dim = passed && !node.hit;
        mark.setAttribute("stroke", lit ? "#eaf4ff" : "rgba(126, 182, 255, 0.28)");
        mark.setAttribute("fill", lit ? "rgba(201, 228, 255, 0.18)" : "rgba(12, 20, 36, 0.2)");
        mark.setAttribute("opacity", dim ? "0.16" : lit ? "1" : "0.35");
      });
      if (link && pair.length >= 2) {
        link.setAttribute("opacity", p > 0.78 ? "0.85" : "0");
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [nodes, pair.length]);

  return (
    <svg ref={root} className={styles.svg} viewBox="0 0 1440 760" role="img" aria-label="匹配扫描">
      <text className={styles.aux} x="1080" y="86">
        SCAN / FILTER
      </text>
      {nodes.map((node, i) => (
        <polygon
          key={`${node.x}-${i}`}
          data-cell="true"
          points={`0,${-node.s} ${node.s},0 0,${node.s} ${-node.s},0`}
          transform={`translate(${node.x} ${node.y})`}
          fill="rgba(12, 20, 36, 0.2)"
          stroke="rgba(126, 182, 255, 0.28)"
          strokeWidth="1"
        />
      ))}
      {pair.length >= 2 ? (
        <line
          data-confirm="true"
          x1={pair[0].x}
          y1={pair[0].y}
          x2={pair[1].x}
          y2={pair[1].y}
          stroke="#eaf4ff"
          strokeWidth="1.1"
          opacity="0"
        />
      ) : null}
      <line
        ref={scan}
        x1="140"
        y1="70"
        x2="140"
        y2="700"
        className={styles.scan}
      />
    </svg>
  );
}
