import { useEffect, useRef } from "react";
import gsap from "gsap";
import { CampusSilhouette } from "./CampusSilhouette";
import styles from "./TimeTracks.module.css";

const TRACKS = [
  { y: 150, times: ["08:00", "10:00", "14:00"], strong: true, flow: true },
  { y: 250, times: ["09:00", "11:30", "15:40"], strong: false, flow: false },
  { y: 350, times: ["07:50", "13:00", "16:20"], strong: true, flow: true },
  { y: 450, times: ["08:40", "12:10", "17:00"], strong: false, flow: false },
  { y: 550, times: ["09:20", "14:30", "18:10"], strong: false, flow: false }
];

const EVENTS = [
  { x: 560, y: 150, title: "发布课程", mark: "01" },
  { x: 820, y: 150, title: "申请代课", mark: "02", current: true },
  { x: 1080, y: 150, title: "双方确认", mark: "03" },
  { x: 680, y: 250, title: "" },
  { x: 980, y: 250, title: "" },
  { x: 620, y: 350, title: "" },
  { x: 900, y: 350, title: "" },
  { x: 1120, y: 350, title: "" },
  { x: 740, y: 450, title: "" },
  { x: 1040, y: 450, title: "" },
  { x: 600, y: 550, title: "" },
  { x: 860, y: 550, title: "" },
  { x: 1140, y: 550, title: "" }
];

const TIES: [number, number][] = [
  [0, 3],
  [1, 4],
  [3, 6],
  [4, 6],
  [6, 8],
  [7, 9],
  [8, 11],
  [9, 12]
];

const DUST = Array.from({ length: 56 }, (_, i) => {
  const n = Math.sin(i * 19.13) * 43758.5453;
  const u = n - Math.floor(n);
  const m = Math.sin(i * 47.11) * 23421.631;
  const v = m - Math.floor(m);
  return { x: 400 + u * 860, y: 60 + v * 560, s: 1.1 + u * 1.6 };
});

const TICKS = TRACKS.flatMap((track) =>
  [520, 640, 760, 880, 1000, 1120].map((x) => ({ x, y: track.y }))
);

export function TimeTracks() {
  const root = useRef<SVGSVGElement>(null);

  useEffect(() => {
    const svg = root.current;
    if (!svg) {
      return;
    }
    const ctx = gsap.context(() => {
      gsap.set("[data-rail]", { strokeDashoffset: 1100 });
      gsap.set("[data-event]", { opacity: 0.15 });
      gsap.set("[data-time]", { opacity: 0.12 });
      gsap.set("[data-flow]", { opacity: 0 });
      gsap.set("[data-tie]", { opacity: 0 });
      gsap.set("[data-campus]", { opacity: 0 });
    }, svg);
    return () => ctx.revert();
  }, []);

  const pathAt = (y: number) => `M460 ${y} C 700 ${y - 16}, 940 ${y + 14}, 1220 ${y}`;

  return (
    <svg ref={root} className={styles.svg} viewBox="0 0 1280 640" role="img" aria-label="课程时间轨道">
      <ellipse cx="860" cy="360" rx="520" ry="310" fill="rgba(61, 111, 232, 0.1)" />
      <ellipse cx="640" cy="220" rx="240" ry="140" fill="rgba(12, 32, 64, 0.28)" />
      <g data-campus="true" transform="translate(390 478) scale(1.18)" opacity="0.5">
        <CampusSilhouette opacity={0.55} />
      </g>
      {TICKS.map((tick) => (
        <line
          key={`${tick.x}-${tick.y}`}
          x1={tick.x}
          y1={tick.y - 7}
          x2={tick.x}
          y2={tick.y + 7}
          className={styles.tick}
        />
      ))}
      {DUST.map((dot) => (
        <polygon
          key={`${dot.x}-${dot.y}`}
          className={styles.dust}
          points={`${dot.x},${dot.y - dot.s} ${dot.x + dot.s},${dot.y} ${dot.x},${dot.y + dot.s} ${dot.x - dot.s},${dot.y}`}
        />
      ))}
      <text className={styles.aux} x="980" y="72">
        TIME / COURSE
      </text>
      {TRACKS.map((track) => (
        <g key={track.y}>
          <path
            data-rail="true"
            d={pathAt(track.y)}
            className={track.strong ? styles.rail : styles.railDim}
            strokeDasharray="1100"
          />
          {track.flow ? (
            <path data-flow="true" d={pathAt(track.y)} className={styles.flow} />
          ) : null}
          {track.times.map((time, t) => (
            <text
              key={time}
              data-time="true"
              className={styles.time}
              x={[500, 940, 1200][t]}
              y={track.y - 26}
            >
              {time}
            </text>
          ))}
        </g>
      ))}
      {TIES.map(([a, b], i) => (
        <line
          key={`tie-${i}`}
          data-tie="true"
          x1={EVENTS[a].x}
          y1={EVENTS[a].y}
          x2={EVENTS[b].x}
          y2={EVENTS[b].y}
          className={styles.tie}
        />
      ))}
      {EVENTS.map((event) => (
        <g key={`${event.x}-${event.y}`} data-event="true" data-step={event.mark ?? undefined} transform={`translate(${event.x} ${event.y})`}>
          {event.current ? (
            <polygon
              points="0,-11 10,0 0,11 -10,0"
              fill="rgba(201, 228, 255, 0.16)"
              stroke="#eaf4ff"
              strokeWidth="1.3"
            />
          ) : (
            <polygon
              points="0,-7 7,0 0,7 -7,0"
              fill="#0c1424"
              stroke={event.title ? "#9fd0ff" : "rgba(126, 182, 255, 0.4)"}
              strokeWidth="1"
            />
          )}
          {event.mark ? (
            <text className={styles.mark} x="0" y="-22">
              {event.mark}
            </text>
          ) : null}
          {event.title ? (
            <text className={styles.title} x="0" y="32">
              {event.title}
            </text>
          ) : null}
        </g>
      ))}
      <text className={styles.now} x="820" y="196">
        AVAILABLE
      </text>
    </svg>
  );
}
