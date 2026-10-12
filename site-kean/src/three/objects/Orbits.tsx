import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import { Line } from "@react-three/drei";
import * as THREE from "three";

type Props = {
  bold?: boolean;
};

function ellipse(rx: number, rz: number, steps = 96): [number, number, number][] {
  const pts: [number, number, number][] = [];
  for (let i = 0; i <= steps; i += 1) {
    const t = (i / steps) * Math.PI * 2;
    pts.push([Math.cos(t) * rx, 0, Math.sin(t) * rz]);
  }
  return pts;
}

function SoftBead({ size }: { size: number }) {
  return (
    <>
      <mesh>
        <sphereGeometry args={[size, 16, 16]} />
        <meshBasicMaterial color="#eaf4ff" transparent opacity={0.72} depthWrite={false} />
      </mesh>
      <mesh>
        <sphereGeometry args={[size * 2.4, 16, 16]} />
        <meshBasicMaterial color="#9fd0ff" transparent opacity={0.12} depthWrite={false} />
      </mesh>
    </>
  );
}

export function Orbits({ bold = false }: Props) {
  const group = useRef<THREE.Group>(null);
  const beadA = useRef<THREE.Group>(null);
  const beadB = useRef<THREE.Group>(null);
  const ringA = useMemo(() => ellipse(2.42, 0.88), []);
  const ringB = useMemo(() => ellipse(2.72, 1.08), []);
  const ringC = useMemo(() => ellipse(3.04, 0.74), []);

  useFrame((state, delta) => {
    if (group.current) {
      group.current.rotation.z += delta * (bold ? 0.028 : 0.04);
      if (bold) {
        group.current.rotation.y += delta * 0.01;
      }
    }
    if (!bold) {
      return;
    }
    const t = state.clock.elapsedTime;
    const a = t * 0.16;
    const b = t * 0.11 + 1.4;
    if (beadA.current) {
      beadA.current.position.set(Math.cos(a) * 2.42, 0, Math.sin(a) * 0.88);
    }
    if (beadB.current) {
      beadB.current.position.set(Math.cos(b) * 2.72, 0, Math.sin(b) * 1.08);
    }
  });

  if (!bold) {
    return (
      <group ref={group}>
        <mesh rotation={[Math.PI / 2.4, 0.2, 0.15]}>
          <torusGeometry args={[2.15, 0.006, 8, 128]} />
          <meshBasicMaterial color="#8ec5ff" transparent opacity={0.38} />
        </mesh>
        <mesh rotation={[1.15, 0.8, -0.4]}>
          <torusGeometry args={[2.38, 0.004, 8, 128]} />
          <meshBasicMaterial color="#5b8fff" transparent opacity={0.28} />
        </mesh>
        <mesh rotation={[0.4, -0.6, 1.1]}>
          <torusGeometry args={[2.55, 0.003, 8, 96]} />
          <meshBasicMaterial color="#c9e4ff" transparent opacity={0.16} />
        </mesh>
      </group>
    );
  }

  return (
    <group ref={group}>
      <group rotation={[0.18, 0.08, 0.12]}>
        <Line points={ringA} color="#dcebf5" lineWidth={1.5} transparent opacity={0.38} toneMapped={false} />
        <group ref={beadA}>
          <SoftBead size={0.05} />
        </group>
      </group>
      <group rotation={[1.02, 0.42, -0.22]}>
        <Line points={ringB} color="#c9e4ff" lineWidth={1.45} transparent opacity={0.26} toneMapped={false} />
        <group ref={beadB}>
          <SoftBead size={0.038} />
        </group>
      </group>
      <group rotation={[0.48, -0.36, 0.86]}>
        <Line points={ringC} color="#dcebf5" lineWidth={1.35} transparent opacity={0.16} toneMapped={false} />
      </group>
    </group>
  );
}
