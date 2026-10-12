import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";

type Props = {
  count: number;
};

export function Particles({ count }: Props) {
  const points = useRef<THREE.Points>(null);
  const travelers = useRef<THREE.Points>(null);

  const shell = useMemo(() => {
    const positions = new Float32Array(count * 3);
    for (let i = 0; i < count; i += 1) {
      const r = 2.05 + Math.random() * 1.55;
      const theta = Math.acos(2 * Math.random() - 1);
      const phi = Math.random() * Math.PI * 2;
      positions[i * 3] = r * Math.sin(theta) * Math.cos(phi);
      positions[i * 3 + 1] = r * Math.sin(theta) * Math.sin(phi);
      positions[i * 3 + 2] = r * Math.cos(theta);
    }
    return positions;
  }, [count]);

  const orbitCount = Math.min(36, Math.floor(count / 40));
  const orbit = useMemo(() => {
    const positions = new Float32Array(orbitCount * 3);
    const meta = new Float32Array(orbitCount * 3);
    for (let i = 0; i < orbitCount; i += 1) {
      meta[i * 3] = Math.random() * Math.PI * 2;
      meta[i * 3 + 1] = 0.18 + Math.random() * 0.22;
      meta[i * 3 + 2] = i % 3;
    }
    return { positions, meta };
  }, [orbitCount]);

  useFrame((_, delta) => {
    if (points.current) {
      points.current.rotation.y += delta * 0.03;
    }
    if (!travelers.current) {
      return;
    }
    const pos = travelers.current.geometry.attributes.position;
    for (let i = 0; i < orbitCount; i += 1) {
      orbit.meta[i * 3] += delta * orbit.meta[i * 3 + 1];
      const a = orbit.meta[i * 3];
      const ring = orbit.meta[i * 3 + 2];
      const rx = 2.15 + ring * 0.18;
      const ry = 1.35 + ring * 0.12;
      if (ring === 0) {
        pos.setXYZ(i, Math.cos(a) * rx, Math.sin(a) * ry * 0.28, Math.sin(a) * rx * 0.42);
      } else if (ring === 1) {
        pos.setXYZ(i, Math.sin(a) * rx * 0.4, Math.cos(a) * ry, Math.cos(a) * rx * 0.55);
      } else {
        pos.setXYZ(i, Math.cos(a) * rx * 0.7, Math.sin(a) * 0.55, Math.sin(a) * rx);
      }
    }
    pos.needsUpdate = true;
  });

  return (
    <group>
      <points ref={points}>
        <bufferGeometry>
          <bufferAttribute attach="attributes-position" args={[shell, 3]} />
        </bufferGeometry>
        <pointsMaterial
          color="#b9d8ff"
          size={0.018}
          sizeAttenuation
          transparent
          opacity={0.72}
          depthWrite={false}
        />
      </points>
      <points ref={travelers}>
        <bufferGeometry>
          <bufferAttribute attach="attributes-position" args={[orbit.positions, 3]} />
        </bufferGeometry>
        <pointsMaterial
          color="#eaf4ff"
          size={0.045}
          sizeAttenuation
          transparent
          opacity={0.95}
          depthWrite={false}
        />
      </points>
    </group>
  );
}
