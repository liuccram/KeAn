import { useMemo, useRef } from "react";
import { Canvas, useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { KeanLights } from "./kean/KeanLights";
import { DragSpin } from "./kean/DragSpin";
import { nearbyProgress } from "../lib/progress";
import type { GpuTier } from "../hooks/useGpuTier";

type Course = {
  x: number;
  y: number;
  z: number;
  near: number;
  size: number;
  trail: boolean;
};

const COURSES: Course[] = [
  { x: -1.38, y: 0.28, z: 0.52, near: 0.06, size: 0.11, trail: true },
  { x: 1.52, y: -0.22, z: 0.68, near: 0.14, size: 0.1, trail: true },
  { x: 0.18, y: 0.66, z: -1.18, near: 0.22, size: 0.095, trail: true },
  { x: -1.82, y: -0.36, z: -0.4, near: 0.3, size: 0.088, trail: true },
  { x: 1.88, y: 0.42, z: -0.74, near: 0.38, size: 0.09, trail: false },
  { x: -0.48, y: 0.88, z: 1.22, near: 0.46, size: 0.078, trail: false },
  { x: 1.02, y: -0.7, z: 1.36, near: 0.54, size: 0.082, trail: false },
  { x: -2.02, y: 0.16, z: 0.98, near: 0.62, size: 0.07, trail: false },
  { x: 2.08, y: 0.08, z: 0.28, near: 0.7, size: 0.068, trail: false },
  { x: -0.16, y: -0.86, z: -1.42, near: 0.78, size: 0.064, trail: false },
  { x: 0.54, y: 1.05, z: 0.14, near: 0.86, size: 0.066, trail: false }
];

const TRAILS = COURSES.filter((item) => item.trail);

function FieldDust({ count }: { count: number }) {
  const points = useRef<THREE.Points>(null);
  const shell = useMemo(() => {
    const positions = new Float32Array(count * 3);
    for (let i = 0; i < count; i += 1) {
      positions[i * 3] = (Math.random() - 0.5) * 6.4;
      positions[i * 3 + 1] = (Math.random() - 0.5) * 2.4;
      positions[i * 3 + 2] = (Math.random() - 0.5) * 4.8;
    }
    return positions;
  }, [count]);
  useFrame((_, delta) => {
    if (points.current) {
      points.current.rotation.y += delta * 0.012;
    }
  });
  return (
    <points ref={points}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[shell, 3]} />
      </bufferGeometry>
      <pointsMaterial color="#b9d8ff" size={0.016} sizeAttenuation transparent opacity={0.42} depthWrite={false} />
    </points>
  );
}

function CampusGhost() {
  const geo = useMemo(() => {
    const positions: number[] = [];
    const buildings = [
      { x: -2.6, z: -2.3, w: 0.42, h: 1.05 },
      { x: -1.9, z: -2.5, w: 0.3, h: 0.7 },
      { x: -1.2, z: -2.2, w: 0.5, h: 1.25 },
      { x: -0.4, z: -2.55, w: 0.28, h: 0.58 },
      { x: 0.35, z: -2.35, w: 0.46, h: 0.92 }
    ];
    buildings.forEach((b) => {
      const y0 = -0.85;
      const x0 = b.x;
      const x1 = b.x + b.w;
      const z = b.z;
      const y1 = y0 + b.h;
      positions.push(x0, y0, z, x0, y1, z, x1, y0, z, x1, y1, z, x0, y0, z, x1, y0, z, x0, y1, z, x1, y1, z);
    });
    return new Float32Array(positions);
  }, []);
  return (
    <lineSegments position={[2.1, -0.15, -0.4]}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[geo, 3]} />
      </bufferGeometry>
      <lineBasicMaterial color="#8ec5ff" transparent opacity={0.08} />
    </lineSegments>
  );
}

function Field({ tier }: { tier: GpuTier }) {
  const nodes = useRef<THREE.Group>(null);
  const trails = useRef<THREE.LineSegments>(null);
  const scan = useRef<THREE.Points>(null);
  const trailGeom = useMemo(() => new Float32Array(TRAILS.length * 6), []);
  const scanCount = tier === "high" ? 22 : 12;
  const scanMeta = useMemo(() => {
    const meta = new Float32Array(scanCount * 4);
    const positions = new Float32Array(scanCount * 3);
    for (let i = 0; i < scanCount; i += 1) {
      const phi = (i / scanCount) * Math.PI * 2;
      meta[i * 4] = Math.random();
      meta[i * 4 + 1] = 0.08 + Math.random() * 0.07;
      meta[i * 4 + 2] = Math.cos(phi);
      meta[i * 4 + 3] = Math.sin(phi);
    }
    return { meta, positions };
  }, [scanCount]);

  useFrame((_, delta) => {
    const t = performance.now() / 1000;
    const p = nearbyProgress.value;
    if (nodes.current) {
      nodes.current.children.forEach((child, i) => {
        const course = COURSES[i];
        if (!course) {
          return;
        }
        const appear = THREE.MathUtils.smoothstep(p, course.near, course.near + 0.2);
        const closeness = 1 - course.near;
        child.visible = appear > 0.02;
        child.scale.setScalar(appear * (0.85 + closeness * 0.4));
        child.position.set(course.x, course.y + Math.sin(t * 0.24 + i) * 0.04 * appear, course.z);
        child.rotation.y = t * 0.08 + i;
        child.traverse((obj) => {
          const mesh = obj as THREE.Mesh;
          if (!mesh.material || Array.isArray(mesh.material)) {
            return;
          }
          const mat = mesh.material as THREE.MeshBasicMaterial;
          if ("opacity" in mat) {
            mat.opacity = appear * (mat.wireframe ? 0.35 + closeness * 0.25 : 0.45 + closeness * 0.5);
          }
        });
      });
    }
    if (trails.current) {
      const pos = trails.current.geometry.attributes.position;
      TRAILS.forEach((course, i) => {
        const appear = THREE.MathUtils.smoothstep(p, course.near, course.near + 0.24);
        pos.setXYZ(i * 2, 0, 0, 0);
        pos.setXYZ(i * 2 + 1, course.x * appear, course.y * appear, course.z * appear);
      });
      pos.needsUpdate = true;
      const mat = trails.current.material as THREE.LineBasicMaterial;
      mat.opacity = 0.05 + p * 0.1;
    }
    if (scan.current) {
      const pos = scan.current.geometry.attributes.position;
      const reach = 0.5 + p * 2.2;
      for (let i = 0; i < scanCount; i += 1) {
        scanMeta.meta[i * 4] = (scanMeta.meta[i * 4] + delta * scanMeta.meta[i * 4 + 1]) % 1;
        const u = scanMeta.meta[i * 4];
        const r = u * reach;
        pos.setXYZ(i, scanMeta.meta[i * 4 + 2] * r, (u - 0.5) * 0.35, scanMeta.meta[i * 4 + 3] * r);
      }
      pos.needsUpdate = true;
    }
  });

  return (
    <group>
      <mesh rotation={[Math.PI / 2.15, 0, 0]}>
        <ringGeometry args={[0.2, 0.214, 6]} />
        <meshBasicMaterial color="#c9e4ff" transparent opacity={0.8} side={THREE.DoubleSide} />
      </mesh>
      <mesh>
        <tetrahedronGeometry args={[0.07, 0]} />
        <meshBasicMaterial color="#eaf4ff" wireframe transparent opacity={0.85} />
      </mesh>
      {[1.15, 2.05, 2.95].map((radius) => (
        <mesh key={radius} rotation={[0.22, 0.15, 0]}>
          <ringGeometry args={[radius, radius + 0.01, 72]} />
          <meshBasicMaterial color="#7eb6ff" transparent opacity={0.07} side={THREE.DoubleSide} />
        </mesh>
      ))}
      <lineSegments ref={trails}>
        <bufferGeometry>
          <bufferAttribute attach="attributes-position" args={[trailGeom, 3]} />
        </bufferGeometry>
        <lineBasicMaterial color="#8ec5ff" transparent opacity={0.08} />
      </lineSegments>
      <group ref={nodes}>
        {COURSES.map((course) => (
          <group key={`${course.x}-${course.z}`} position={[course.x, course.y, course.z]} scale={0.001}>
            <mesh>
              <tetrahedronGeometry args={[course.size, 0]} />
              <meshBasicMaterial color="#7eb6ff" transparent opacity={0} />
            </mesh>
            <mesh>
              <tetrahedronGeometry args={[course.size * 1.04, 0]} />
              <meshBasicMaterial color="#9fd0ff" wireframe transparent opacity={0} />
            </mesh>
          </group>
        ))}
      </group>
      <points ref={scan}>
        <bufferGeometry>
          <bufferAttribute attach="attributes-position" args={[scanMeta.positions, 3]} />
        </bufferGeometry>
        <pointsMaterial color="#eaf4ff" size={0.034} sizeAttenuation transparent opacity={0.45} depthWrite={false} />
      </points>
    </group>
  );
}

export function NearbyScene({ tier }: { tier: GpuTier }) {
  return (
    <Canvas
      dpr={tier === "high" ? [1, 1.25] : 1}
      camera={{ position: [0, 0.16, 6.1], fov: 38 }}
      gl={{ antialias: false, alpha: true, powerPreference: "high-performance" }}
      style={{ width: "100%", height: "100%", touchAction: "pan-y" }}
      onCreated={({ gl }) => {
        gl.setClearColor("#05070c", 0);
      }}
    >
      <KeanLights rich={tier === "high"} fogNear={5.4} fogFar={12.2} />
      <FieldDust count={tier === "high" ? 280 : 72} />
      <CampusGhost />
      <DragSpin
        autoY={0.022}
        baseX={0.08}
        baseY={-0.16}
        position={tier === "high" ? [1.08, 0.04, 0] : [0.12, 0.06, 0]}
        enabled={tier === "high"}
      >
        <Field tier={tier} />
      </DragSpin>
    </Canvas>
  );
}
