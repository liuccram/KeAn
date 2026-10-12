import { useEffect, useId, useState } from "react";
import styles from "./Navbar.module.css";

const LINKS = [
  { href: "#top", label: "首页" },
  { href: "#network", label: "功能介绍" },
  { href: "#match", label: "特色优势" },
  { href: "#download", label: "下载" },
  { href: "#about", label: "关于我们" }
];

export function Navbar() {
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);
  const menuId = useId();

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    const mq = window.matchMedia("(min-width: 861px)");
    const onChange = () => {
      if (mq.matches) {
        setOpen(false);
      }
    };
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      document.body.style.overflow = "";
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const go = () => setOpen(false);

  return (
    <>
      <header className={`${styles.bar} ${scrolled || open ? styles.glass : ""}`}>
        <a className={styles.brand} href="#top" onClick={go}>
          课安
        </a>
        <nav className={styles.nav} aria-label="主导航">
          {LINKS.map((link) => (
            <a key={link.href} href={link.href}>
              {link.label}
            </a>
          ))}
        </nav>
        <a className={styles.download} href="#download">
          下载 App
        </a>
        <button
          className={`${styles.menuBtn} ${open ? styles.menuBtnOpen : ""}`}
          type="button"
          aria-label={open ? "关闭菜单" : "打开菜单"}
          aria-expanded={open}
          aria-controls={menuId}
          onClick={() => setOpen((value) => !value)}
        >
          <span />
          <span />
        </button>
      </header>
      <div
        className={`${styles.scrim} ${open ? styles.scrimOpen : ""}`}
        onClick={go}
        aria-hidden="true"
      />
      <nav
        id={menuId}
        className={`${styles.panel} ${open ? styles.panelOpen : ""}`}
        aria-label="移动导航"
        aria-hidden={!open}
      >
        {LINKS.map((link) => (
          <a key={link.href} href={link.href} onClick={go}>
            {link.label}
          </a>
        ))}
      </nav>
    </>
  );
}
