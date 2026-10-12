import { useEffect, useState } from "react";
import { useMediaQuery } from "./useMediaQuery";
import { useReducedMotion } from "./useReducedMotion";

export type GpuTier = "high" | "medium" | "low";

function probeWebgl(): boolean {
  try {
    const canvas = document.createElement("canvas");
    return Boolean(canvas.getContext("webgl2") || canvas.getContext("webgl"));
  } catch {
    return false;
  }
}

function saveData(): boolean {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  return Boolean(connection?.saveData);
}

export function useGpuTier(): GpuTier {
  const reduced = useReducedMotion();
  const mobile = useMediaQuery("(max-width: 768px)");
  const [webgl, setWebgl] = useState(true);

  useEffect(() => {
    setWebgl(probeWebgl());
  }, []);

  if (reduced || !webgl || saveData()) {
    return "low";
  }
  if (mobile) {
    return "medium";
  }
  return "high";
}
