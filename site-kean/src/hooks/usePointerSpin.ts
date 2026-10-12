import { useEffect, type RefObject } from "react";

export function usePointerSpin(ref: RefObject<HTMLElement | null>, max = 10) {
  useEffect(() => {
    const el = ref.current;
    if (!el || window.matchMedia("(max-width: 768px)").matches) {
      return;
    }
    const pointer = { active: false, lx: 0, ly: 0, tx: 0, ty: 0, cx: 0, cy: 0 };
    let frame = 0;

    const tick = () => {
      pointer.cx += (pointer.tx - pointer.cx) * 0.08;
      pointer.cy += (pointer.ty - pointer.cy) * 0.08;
      el.style.transform = `perspective(1400px) rotateY(${pointer.cy}deg) rotateX(${-pointer.cx}deg)`;
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);

    const down = (event: PointerEvent) => {
      if (event.pointerType !== "mouse" && event.pointerType !== "pen") {
        return;
      }
      if (event.button !== 0) {
        return;
      }
      pointer.active = true;
      pointer.lx = event.clientX;
      pointer.ly = event.clientY;
    };
    const move = (event: PointerEvent) => {
      if (!pointer.active) {
        return;
      }
      pointer.ty = Math.max(-max, Math.min(max, pointer.ty + (event.clientX - pointer.lx) * 0.04));
      pointer.tx = Math.max(-max, Math.min(max, pointer.tx + (event.clientY - pointer.ly) * 0.03));
      pointer.lx = event.clientX;
      pointer.ly = event.clientY;
    };
    const up = () => {
      pointer.active = false;
    };

    el.addEventListener("pointerdown", down);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      cancelAnimationFrame(frame);
      el.removeEventListener("pointerdown", down);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [ref, max]);
}
