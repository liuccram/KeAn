import { useEffect, useState, type RefObject } from "react";

export function useInView(ref: RefObject<Element | null>, margin = "40% 0px", once = false) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }

    const show = () => setVisible(true);
    const rect = el.getBoundingClientRect();
    if (rect.bottom > 0 && rect.top < window.innerHeight) {
      show();
    }

    const io = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          show();
          if (once) {
            io.disconnect();
          }
          return;
        }
        if (!once) {
          setVisible(false);
        }
      },
      { rootMargin: margin, threshold: 0.02 }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [ref, margin, once]);

  return visible;
}
