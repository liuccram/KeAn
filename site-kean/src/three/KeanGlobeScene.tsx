import { Canvas } from "@react-three/fiber";
import { Line } from "@react-three/drei";
import * as THREE from "three";
import { useMemo } from "react";
import { DragSpin } from "./kean/DragSpin";
import { useReducedMotion } from "../hooks/useReducedMotion";

function GlobeGeometry() {
  const latitudes = useMemo(() => {
    return Array.from({ length: 9 }, (_, index) => {
      const latitude = THREE.MathUtils.degToRad(-72 + index * 18);
      const points = Array.from({ length: 97 }, (_, pointIndex) => {
        const longitude = (pointIndex / 96) * Math.PI * 2;
        return new THREE.Vector3(
          1.51 * Math.cos(latitude) * Math.cos(longitude),
          1.51 * Math.sin(latitude),
          1.51 * Math.cos(latitude) * Math.sin(longitude)
        );
      });
      return points;
    });
  }, []);

  const longitudes = useMemo(() => {
    return Array.from({ length: 12 }, (_, index) => {
      const longitude = (index / 12) * Math.PI * 2;
      return Array.from({ length: 65 }, (_, pointIndex) => {
        const latitude = -Math.PI / 2 + (pointIndex / 64) * Math.PI;
        return new THREE.Vector3(
          1.51 * Math.cos(latitude) * Math.cos(longitude),
          1.51 * Math.sin(latitude),
          1.51 * Math.cos(latitude) * Math.sin(longitude)
        );
      });
    });
  }, []);

  const nodes = useMemo(() => {
    const goldenAngle = Math.PI * (3 - Math.sqrt(5));
    return Array.from({ length: 34 }, (_, index) => {
      const y = 1 - (index / 33) * 2;
      const radius = Math.sqrt(1 - y * y);
      const angle = goldenAngle * index;
      return new THREE.Vector3(Math.cos(angle) * radius, y, Math.sin(angle) * radius).multiplyScalar(1.526);
    });
  }, []);

  const connections = useMemo(() => nodes.flatMap((point, index) => {
    const neighbor = nodes[(index + 5) % nodes.length];
    return [[point, neighbor] as [THREE.Vector3, THREE.Vector3]];
  }), [nodes]);

  return (
    <group>
      <mesh>
        <sphereGeometry args={[1.48, 80, 80]} />
        <meshStandardMaterial color="#0c2a52" metalness={0.42} roughness={0.38} emissive="#071a38" emissiveIntensity={0.32} />
      </mesh>
      <mesh>
        <sphereGeometry args={[1.5, 64, 64]} />
        <meshBasicMaterial color="#3185ff" side={THREE.BackSide} transparent opacity={0.17} />
      </mesh>
      {latitudes.map((points, index) => <Line key={`lat-${index}`} points={points} color="#5da8ff" transparent opacity={index === 4 ? 0.42 : 0.2} lineWidth={index === 4 ? 1.2 : 0.75} />)}
      {longitudes.map((points, index) => <Line key={`long-${index}`} points={points} color="#70b4ff" transparent opacity={index % 3 === 0 ? 0.3 : 0.16} lineWidth={0.75} />)}
      {connections.map(([start, end], index) => <Line key={`link-${index}`} points={[start, end]} color="#64dcff" transparent opacity={0.22} lineWidth={0.8} />)}
      {nodes.map((point, index) => (
        <mesh key={`node-${index}`} position={point.toArray()}>
          <sphereGeometry args={[index % 7 === 0 ? 0.026 : 0.014, 10, 10]} />
          <meshBasicMaterial color={index % 7 === 0 ? "#b6e9ff" : "#5caeff"} />
        </mesh>
      ))}
      <mesh rotation={[0.76, 0.18, -0.3]}>
        <torusGeometry args={[1.86, 0.006, 8, 160]} />
        <meshBasicMaterial color="#4389e8" transparent opacity={0.42} />
      </mesh>
      <mesh rotation={[-0.48, 0.35, 0.72]}>
        <torusGeometry args={[2.04, 0.004, 8, 160]} />
        <meshBasicMaterial color="#8c79ff" transparent opacity={0.28} />
      </mesh>
    </group>
  );
}

export function KeanGlobeScene() {
  const reducedMotion = useReducedMotion();

  return (
    <Canvas
      dpr={[1, 1.5]}
      camera={{ position: [0, 0, 5.15], fov: 39 }}
      gl={{ alpha: true, antialias: true, powerPreference: "low-power" }}
      fallback={<div className="globeFallback" aria-hidden="true" />}
      style={{ width: "100%", height: "100%", touchAction: "none", cursor: "grab" }}
    >
      <ambientLight intensity={0.72} />
      <directionalLight position={[4, 4, 5]} intensity={1.65} color="#dceeff" />
      <pointLight position={[-3, -1, -3]} intensity={12} color="#347aff" distance={10} />
      <DragSpin autoY={reducedMotion ? 0 : 0.035} baseX={-0.08}>
        <GlobeGeometry />
      </DragSpin>
    </Canvas>
  );
}
