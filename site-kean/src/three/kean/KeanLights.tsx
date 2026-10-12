type Props = {
  rich: boolean;
  fogNear?: number;
  fogFar?: number;
};

export function KeanLights({ rich, fogNear = 5.6, fogFar = 12.4 }: Props) {
  return (
    <>
      <fog attach="fog" args={["#05070c", fogNear, fogFar]} />
      <ambientLight intensity={0.18} />
      <hemisphereLight args={["#d7e9ff", "#05070c", 0.45]} />
      <directionalLight position={[3.4, 3.2, 5.5]} intensity={1.1} color="#eef6ff" />
      <pointLight position={[-3.2, -1.4, -2.2]} intensity={rich ? 16 : 11} color="#2f5fce" distance={12} />
    </>
  );
}
