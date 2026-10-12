import { Canvas } from "@react-three/fiber";
import { LatticeCore } from "./objects/LatticeCore";
import { Orbits } from "./objects/Orbits";
import { Particles } from "./objects/Particles";
import { FarStars } from "./objects/FarStars";
import { DragSpin } from "./kean/DragSpin";
import type { GpuTier } from "../hooks/useGpuTier";

type Props = {
  tier: GpuTier;
};

export function DigitalCoreScene({ tier }: Props) {
  const high = tier === "high";
  const placed: [number, number, number] = high ? [0.7, 0.42, 0] : [0, 0.16, 0];
  const cameraZ = high ? 6.1 : 8.15;
  const fov = high ? 38 : 40;

  return (
    <Canvas
      dpr={high ? [1, 1.5] : [1, 2]}
      gl={{ antialias: !high, alpha: true, powerPreference: "high-performance" }}
      camera={{ position: [0, -0.12, cameraZ], fov }}
      style={{ width: "100%", height: "100%", touchAction: "pan-y", cursor: high ? "grab" : "default" }}
      onCreated={({ gl }) => {
        gl.setClearColor("#05070c", 0);
      }}
    >
      <fog attach="fog" args={["#05070c", high ? 6.8 : 11, high ? 12.8 : 18]} />
      <ambientLight intensity={0.18} />
      <hemisphereLight args={["#d7e9ff", "#05070c", 0.45]} />
      <directionalLight position={[3.4, 3.2, 5.5]} intensity={1.1} color="#eef6ff" />
      <pointLight position={[-3.2, -1.4, -2.2]} intensity={high ? 16 : 10} color="#2f5fce" distance={12} />
      {high ? null : <FarStars count={88} />}
      <DragSpin autoY={0.016} baseX={0.04} position={placed} enabled={high}>
        <LatticeCore rich={high} />
        <Orbits bold={!high} />
        {high ? <Particles count={2400} /> : null}
      </DragSpin>
    </Canvas>
  );
}
