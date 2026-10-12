import { useEffect, useRef, type RefObject } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useReducedMotion } from "./useReducedMotion";

gsap.registerPlugin(ScrollTrigger);

export type RevealVariant = "parse" | "approach" | "flow" | "scan" | "quiet";
export type RevealTimeline = gsap.core.Timeline;

type Options = {
  visual?: (timeline: RevealTimeline) => void;
};

function layers(root: HTMLElement) {
  return {
    index: root.querySelectorAll("[data-reveal='index']"),
    label: root.querySelectorAll("[data-reveal='label']"),
    title: root.querySelectorAll("[data-reveal='title']"),
    body: root.querySelectorAll("[data-reveal='body']"),
    extra: root.querySelectorAll("[data-reveal='extra']")
  };
}

export function useSectionReveal(
  root: RefObject<HTMLElement | null>,
  variant: RevealVariant,
  options: Options = {}
) {
  const reduced = useReducedMotion();
  const visualRef = useRef(options.visual);
  visualRef.current = options.visual;

  useEffect(() => {
    const el = root.current;
    if (!el) {
      return;
    }

    const ctx = gsap.context(() => {
      const parts = layers(el);
      const blur = reduced ? 0 : 2;
      const tl = gsap.timeline({ paused: true });

      gsap.set(parts.index, { autoAlpha: 0, y: reduced ? 0 : 12 });
      gsap.set(parts.label, { autoAlpha: 0, y: reduced ? 0 : 14 });
      gsap.set(parts.body, { autoAlpha: 0, y: reduced ? 0 : 12 });
      gsap.set(parts.extra, { autoAlpha: 0, y: reduced ? 0 : 10 });

      if (variant === "approach") {
        gsap.set(parts.title, {
          autoAlpha: 0,
          y: reduced ? 0 : 8,
          scale: reduced ? 1 : 0.98,
          filter: `blur(${blur}px)`,
          transformOrigin: "50% 50%"
        });
      } else if (variant === "flow") {
        gsap.set(parts.title, {
          autoAlpha: 0,
          y: reduced ? 0 : 12,
          clipPath: reduced ? "none" : "inset(0 0 18% 0)"
        });
      } else if (variant === "scan") {
        gsap.set(parts.title, {
          autoAlpha: 0,
          y: reduced ? 0 : 12,
          clipPath: reduced ? "none" : "inset(0 22% 0 0)",
          filter: `blur(${blur}px)`
        });
      } else if (variant === "quiet") {
        gsap.set(parts.title, { autoAlpha: 0, y: reduced ? 0 : 8 });
      } else {
        gsap.set(parts.title, {
          autoAlpha: 0,
          y: reduced ? 0 : 16,
          filter: `blur(${blur}px)`
        });
      }

      const fade = (target: NodeListOf<Element>, at: number, duration: number) => {
        if (target.length === 0) {
          return;
        }
        tl.to(
          target,
          {
            autoAlpha: 1,
            y: 0,
            scale: 1,
            filter: "blur(0px)",
            clipPath: "inset(0 0 0 0)",
            duration,
            ease: variant === "quiet" ? "power1.out" : "power2.out"
          },
          at
        );
      };

      if (variant === "parse") {
        fade(parts.index, 0, 0.22);
        fade(parts.label, 0.08, 0.26);
        fade(parts.title, 0.2, 0.34);
        fade(parts.body, 0.38, 0.3);
      } else if (variant === "approach") {
        fade(parts.index, 0, 0.2);
        fade(parts.label, 0.08, 0.24);
        fade(parts.title, 0.18, 0.36);
        fade(parts.body, 0.36, 0.3);
      } else if (variant === "flow") {
        fade(parts.index, 0, 0.2);
        fade(parts.label, 0.08, 0.24);
        fade(parts.title, 0.22, 0.32);
        fade(parts.body, 0.42, 0.28);
      } else if (variant === "scan") {
        fade(parts.index, 0, 0.18);
        fade(parts.label, 0.1, 0.24);
        fade(parts.title, 0.4, 0.32);
        fade(parts.body, 0.62, 0.26);
      } else {
        fade(parts.index, 0, 0.36);
        fade(parts.label, 0.12, 0.4);
        fade(parts.title, 0.28, 0.44);
        fade(parts.body, 0.48, 0.42);
        fade(parts.extra, 0.4, 0.4);
      }

      visualRef.current?.(tl);

      const play = (self?: ScrollTrigger) => {
        const fast = Boolean(self && Math.abs(self.getVelocity()) > 1700);
        if (reduced) {
          tl.progress(1);
          return;
        }
        tl.timeScale(fast ? 2.6 : 1);
        if (fast) {
          tl.progress(1);
          return;
        }
        tl.play(0);
      };

      ScrollTrigger.create({
        trigger: el,
        start: "top 68%",
        end: "bottom 24%",
        invalidateOnRefresh: true,
        onEnter: (self) => play(self),
        onEnterBack: (self) => play(self),
        onLeaveBack: () => {
          tl.timeScale(1);
          tl.pause(0);
        },
        onRefresh: (self) => {
          if (self.isActive || self.progress > 0) {
            tl.progress(1);
          }
        }
      });
    }, el);

    requestAnimationFrame(() => ScrollTrigger.refresh());
    return () => ctx.revert();
  }, [root, variant, reduced]);
}
