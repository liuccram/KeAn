import { useEffect, useRef, type ReactNode } from "react";
import { useFrame, useThree } from "@react-three/fiber";
import * as THREE from "three";

type Props = {
  children: ReactNode;
  autoY?: number;
  baseX?: number;
  baseY?: number;
  position?: [number, number, number];
  ignoreBottom?: number;
  enabled?: boolean;
};

export function DragSpin({
  children,
  autoY = 0.04,
  baseX = 0,
  baseY = 0,
  position = [0, 0, 0],
  ignoreBottom = 0,
  enabled = true
}: Props) {
  const group = useRef<THREE.Group>(null);
  const { gl } = useThree();
  const current = useRef({ x: 0, y: 0 });
  const target = useRef({ x: 0, y: 0 });
  const auto = useRef(0);
  const pointer = useRef({ active: false, lx: 0, ly: 0 });
  const ignoreRef = useRef(ignoreBottom);
  ignoreRef.current = ignoreBottom;

  useEffect(() => {
    if (!enabled) {
      gl.domElement.style.cursor = "default";
      return;
    }
    const el = gl.domElement;
    // Keep touch gestures inside the globe so a finger drag rotates it reliably.
    el.style.touchAction = "none";
    el.style.cursor = "grab";

    const down = (event: PointerEvent) => {
      if (event.button !== 0) {
        return;
      }
      const rect = el.getBoundingClientRect();
      const cut = ignoreRef.current;
      if (cut > 0 && event.clientY > rect.top + rect.height * (1 - cut)) {
        return;
      }
      pointer.current = { active: true, lx: event.clientX, ly: event.clientY };
      el.style.cursor = "grabbing";
    };
    const move = (event: PointerEvent) => {
      if (!pointer.current.active) {
        return;
      }
      const dx = event.clientX - pointer.current.lx;
      const dy = event.clientY - pointer.current.ly;
      pointer.current.lx = event.clientX;
      pointer.current.ly = event.clientY;
      // Drag sensitivity per pixel of pointer travel, raised ~1.4x from 0.005 / 0.0036.
      target.current.y += dx * 0.007;
      target.current.x = THREE.MathUtils.clamp(target.current.x + dy * 0.005, -0.82, 0.82);
    };
    const up = () => {
      pointer.current.active = false;
      el.style.cursor = "grab";
    };

    el.addEventListener("pointerdown", down);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    return () => {
      el.removeEventListener("pointerdown", down);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
    };
  }, [gl, enabled]);

  useFrame((_, delta) => {
    if (!group.current) {
      return;
    }
    auto.current += delta * autoY;
    current.current.x = THREE.MathUtils.damp(current.current.x, target.current.x, 5.5, delta);
    current.current.y = THREE.MathUtils.damp(current.current.y, target.current.y, 5.5, delta);
    group.current.rotation.x = baseX + current.current.x;
    group.current.rotation.y = baseY + auto.current + current.current.y;
  });

  return (
    <group ref={group} position={position}>
      {children}
    </group>
  );
}
