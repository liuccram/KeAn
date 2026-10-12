import { useRef } from "react";
import { useFrame } from "@react-three/fiber";
import { Edges } from "@react-three/drei";
import * as THREE from "three";

type Props = {
  rich: boolean;
};

const SPARKS: [number, number, number][] = [
  [0, 1.56, 0],
  [1.4, 0.62, 0.28],
  [-0.92, 0.58, 1.08],
  [0.72, -0.78, 1.12]
];

export function LatticeCore({ rich }: Props) {
  const shell = useRef<THREE.Group>(null);
  const heart = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.elapsedTime;
    if (shell.current) {
      shell.current.rotation.y = t * 0.08;
      shell.current.rotation.x = Math.sin(t * 0.12) * 0.08;
    }
    if (heart.current) {
      const pulse = 0.9 + Math.sin(t * 0.85) * 0.05;
      heart.current.scale.setScalar(pulse);
    }
  });

  if (rich) {
    return (
      <group ref={shell}>
        <mesh>
          <icosahedronGeometry args={[1.55, 1]} />
          <meshPhysicalMaterial
            color="#17325d"
            roughness={0.18}
            metalness={0.35}
            transmission={0.72}
            thickness={1.15}
            transparent
            opacity={0.92}
            ior={1.18}
            envMapIntensity={0.4}
          />
        </mesh>
        <mesh>
          <icosahedronGeometry args={[1.56, 1]} />
          <meshBasicMaterial color="#9fd0ff" wireframe transparent opacity={0.55} />
        </mesh>
        <mesh>
          <icosahedronGeometry args={[1.72, 0]} />
          <meshBasicMaterial color="#7eb6ff" wireframe transparent opacity={0.12} />
        </mesh>
        <mesh ref={heart}>
          <icosahedronGeometry args={[0.42, 1]} />
          <meshBasicMaterial color="#e7f3ff" transparent opacity={0.92} />
        </mesh>
        <pointLight color="#cfe6ff" intensity={8} distance={6} />
      </group>
    );
  }

  return (
    <group ref={shell}>
      <mesh>
        <icosahedronGeometry args={[1.52, 2]} />
        <meshStandardMaterial
          color="#163056"
          emissive="#3d6fe8"
          emissiveIntensity={0.14}
          roughness={0.38}
          metalness={0.28}
          transparent
          opacity={0.46}
        />
        <Edges threshold={1} lineWidth={1.75} color="#dcebf5" transparent opacity={0.86} toneMapped={false} />
      </mesh>
      <mesh>
        <sphereGeometry args={[1.9, 32, 24]} />
        <meshBasicMaterial color="#5b8fff" transparent opacity={0.055} depthWrite={false} />
      </mesh>
      <mesh ref={heart}>
        <sphereGeometry args={[0.36, 24, 16]} />
        <meshBasicMaterial color="#e7f3ff" transparent opacity={0.16} depthWrite={false} />
      </mesh>
      {SPARKS.map((pos) => (
        <group key={pos.join(",")} position={pos}>
          <mesh>
            <sphereGeometry args={[0.028, 12, 12]} />
            <meshBasicMaterial color="#f4f8ff" transparent opacity={0.7} depthWrite={false} />
          </mesh>
          <mesh>
            <sphereGeometry args={[0.07, 12, 12]} />
            <meshBasicMaterial color="#c9e4ff" transparent opacity={0.16} depthWrite={false} />
          </mesh>
        </group>
      ))}
      <pointLight color="#cfe6ff" intensity={5.2} distance={6} />
    </group>
  );
}
