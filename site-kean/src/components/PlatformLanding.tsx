import { useEffect, useRef, useState } from "react";
import { KeanGlobeScene } from "../three/KeanGlobeScene";
import styles from "./PlatformLanding.module.css";

const navItems = [
  { href: "#product", label: "产品能力" },
  { href: "#workflow", label: "协作流程" },
  { href: "#trust", label: "安全与规则" },
  { href: "#faq", label: "常见问题" }
];

const capabilities = [
  {
    number: "01",
    title: "发布一条清楚的需求",
    body: "课程、时间、地点与酬谢，一次写清。",
    icon: "＋",
    tone: "blue"
  },
  {
    number: "02",
    title: "由发布者选择合适的人",
    body: "查看申请资料，由发布者确认一位代课者。",
    icon: "↗",
    tone: "violet"
  },
  {
    number: "03",
    title: "把课前沟通留在同一处",
    body: "私信确认教室、碰面地点与课程材料。",
    icon: "↔",
    tone: "cyan"
  },
  {
    number: "04",
    title: "完成后留下双向记录",
    body: "完成任务、双方确认，并留下评价记录。",
    icon: "✓",
    tone: "green"
  }
];

const steps = [
  { no: "01", title: "发布需求", text: "课程 · 时间 · 地点" },
  { no: "02", title: "选择并沟通", text: "确认人选 · 对齐安排" },
  { no: "03", title: "完成代课", text: "双方确认 · 留下评价" }
];

const faqs = [
  {
    question: "课安会代收或托管酬谢吗？",
    answer: "不会。酬谢由双方在线下协商和结算。"
  },
  {
    question: "发布需求后，谁来决定由谁代课？",
    answer: "发布者查看申请后选择一位代课者。"
  },
  {
    question: "代课者一定要上传现场照片吗？",
    answer: "按发布需求时的约定执行，课安会展示对应要求。"
  },
  {
    question: "遇到失约或不合适的行为怎么办？",
    answer: "可在应用内举报或反馈；对处理结果有异议时可申诉。"
  }
];

/* Entrance animations replay on every entry, with hysteresis so an element parked
   on the threshold cannot flicker: enter at 14% visible, leave only once fully outside. */
const ENTER_RATIO = 0.14;
const ROOT_MARGIN = "0px 0px -5% 0px";
const EXIT_MARGIN_RATIO = 0.05;

function viewportBottom() {
  return window.innerHeight * (1 - EXIT_MARGIN_RATIO);
}

function fullyOutside(entry: IntersectionObserverEntry, bottom: number) {
  const rect = entry.boundingClientRect;
  return rect.bottom <= 0 || rect.top >= bottom;
}

function BrandMark() {
  return (
    <span className={styles.brandMark} aria-hidden="true">
      <svg viewBox="0 0 32 32" fill="none">
        <path d="M7 9.5h18M7 16h12M7 22.5h8" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" />
        <path d="m20.5 21 2.5 2.5 4-5" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span>
  );
}

function Arrow({ diagonal = false }: { diagonal?: boolean }) {
  return <span aria-hidden="true" className={styles.arrow}>{diagonal ? "↗" : "→"}</span>;
}

export function PlatformLanding() {
  const siteRef = useRef<HTMLDivElement>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [activeFaq, setActiveFaq] = useState<number | null>(0);

  useEffect(() => {
    const site = siteRef.current;
    if (!site) return;
    const nodes = Array.from(site.querySelectorAll<HTMLElement>("[data-reveal]"));
    site.classList.add(styles.motionReady);
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      site.classList.add(styles.motionReduced);
    }
    if (!("IntersectionObserver" in window)) {
      nodes.forEach((node) => node.classList.add(styles.isVisible));
      return;
    }
    const revealed = new WeakSet<HTMLElement>();
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        // Every observed node came from querySelectorAll<HTMLElement>("[data-reveal]").
        const target = entry.target as HTMLElement;
        if (entry.isIntersecting && entry.intersectionRatio >= ENTER_RATIO) {
          if (revealed.has(target)) {
            return;
          }
          revealed.add(target);
          target.classList.add(styles.isVisible);
          return;
        }
        if (!revealed.has(target) || !fullyOutside(entry, viewportBottom())) {
          return;
        }
        revealed.delete(target);
        target.classList.remove(styles.isVisible);
        // Force a style flush while the class is absent so the CSS animation restarts
        // from the first keyframe when the class comes back on the next entry.
        void target.offsetWidth;
      });
    }, { threshold: [0, ENTER_RATIO], rootMargin: ROOT_MARGIN });
    nodes.forEach((node) => observer.observe(node));
    return () => observer.disconnect();
  }, []);

  const closeMenu = () => setMenuOpen(false);

  return (
    <div className={styles.site} id="top" ref={siteRef}>
      <div className={styles.announcement}>
        <span className={styles.announcementDot} />
        <span>课安 · 让临时代课的每一步，都清楚可见</span>
        <a href="#workflow">了解协作流程 <Arrow /></a>
      </div>

      <header className={styles.header}>
        <a className={styles.brand} href="#top" onClick={closeMenu} aria-label="课安首页">
          <BrandMark />
          <span>课安</span>
          <small>KEAN</small>
        </a>
        <nav className={`${styles.nav} ${menuOpen ? styles.navOpen : ""}`} aria-label="主导航">
          {navItems.map((item) => (
            <a key={item.href} href={item.href} onClick={closeMenu}>{item.label}</a>
          ))}
          <a className={styles.mobileCta} href="#download" onClick={closeMenu}>了解客户端 <Arrow /></a>
        </nav>
        <a className={styles.headerCta} href="#download">了解课安 <Arrow /></a>
        <button
          className={`${styles.menuButton} ${menuOpen ? styles.menuButtonOpen : ""}`}
          type="button"
          aria-label={menuOpen ? "关闭导航" : "打开导航"}
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        ><span /><span /></button>
      </header>

      <main>
        <section className={styles.hero} data-reveal>
          <div className={styles.heroGrid} aria-hidden="true" />
          <div className={styles.heroCopy} data-reveal>
            <div className={styles.eyebrow}><span>校园代课互助平台</span><i />一个清晰的协作流程</div>
            <h1>欢迎来到课安</h1>
            <p className={styles.heroLead}>发布需求、申请接手、沟通确认。<br />让课程交接简单而清楚。</p>
            <div className={styles.heroActions}>
              <a className={styles.primaryButton} href="#product">探索课安 <Arrow /></a>
              {/* Secondary hero action: opens the public site in a new tab so the landing page stays put. */}
              <a className={styles.joinButton} href="https://kean.college/#/pages/auth/login" target="_blank" rel="noopener noreferrer">加入课安</a>
              <a className={styles.textButton} href="#workflow">了解如何协作 <Arrow /></a>
            </div>
            <div className={styles.heroFootnote}><span className={styles.statusDot} />发布需求 · 申请接手 · 双方确认</div>
          </div>

          <div className={styles.heroVisual} data-reveal aria-label="可拖曳旋转的课安 3D 地球">
            <div className={styles.globeAura} aria-hidden="true" />
            <div className={styles.globeFrame}>
              <KeanGlobeScene />
              <span className={`${styles.globeLabel} ${styles.globeLabelTop}`}>DRAG TO ROTATE</span>
              <span className={`${styles.globeLabel} ${styles.globeLabelLeft}`}>发布</span>
              <span className={`${styles.globeLabel} ${styles.globeLabelRight}`}>连接</span>
              <span className={`${styles.globeLabel} ${styles.globeLabelBottom}`}>确认</span>
            </div>
            <div className={styles.visualCaption}><span>KEAN / CONNECTED GLOBE</span><span>DRAG ↗</span></div>
          </div>
          <div className={styles.heroScroll}><span />向下了解课安</div>
        </section>

        <section className={styles.signalStrip} aria-label="平台特点" data-reveal>
          <span>为真实课程需求而设计</span>
          <div><i />清楚的任务状态</div><div><i />直接的双方沟通</div><div><i />完整的履约记录</div>
        </section>

        <section className={styles.productSection} id="product" data-reveal>
          <div className={styles.sectionIntro}>
            <div className={styles.eyebrow}><span>PRODUCT / 01</span><i />从发起到完成</div>
            <h2>不止是发消息，<br /><em>而是一套交接方式。</em></h2>
            <p>从发布到完成，关键信息与任务进度都在同一处。</p>
          </div>
          <div className={styles.capabilityGrid}>
            {capabilities.map((item) => (
              <article className={`${styles.capabilityCard} ${styles[item.tone]}`} key={item.number} data-reveal>
                <div className={styles.capabilityTop}><span>{item.number} / 04</span><span className={styles.capabilityIcon}>{item.icon}</span></div>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
                <div className={styles.cardRule}><span /></div>
              </article>
            ))}
          </div>
        </section>

        <section className={styles.workflowSection} id="workflow" data-reveal>
          <div className={styles.workflowTop}>
            <div>
              <div className={styles.eyebrow}><span>HOW IT WORKS / 02</span><i />一条任务的生命周期</div>
              <h2>每一步，<br /><em>都知道下一步。</em></h2>
            </div>
            <p>状态随流程更新，双方始终知道下一步。</p>
          </div>
          <div className={styles.flowLine} aria-hidden="true"><span /><span /><span /></div>
          <div className={styles.steps}>
            {steps.map((step) => (
              <article className={styles.step} key={step.no} data-reveal>
                <span className={styles.stepNumber}>{step.no}</span>
                <h3>{step.title}</h3>
                <p>{step.text}</p>
              </article>
            ))}
          </div>
          <div className={styles.workflowNote}><span>i</span><p>安排由双方沟通确认，课安记录流程。</p></div>
        </section>

        <section className={styles.principlesSection} id="trust" data-reveal>
          <div className={styles.principlesHead}>
            <div className={styles.eyebrow}><span>TRUST & CLARITY / 03</span><i />规则透明，边界清楚</div>
            <h2>互助要方便，<br /><em>规则也要清楚。</em></h2>
          </div>
          <div className={styles.principlesLayout}>
            <div className={styles.principleLead}>
              <div className={styles.shieldMark}>
                <svg viewBox="0 0 64 64" fill="none" aria-hidden="true"><path d="M32 6 52 14v15c0 13.1-8.1 23.4-20 29C20.1 52.4 12 42.1 12 29V14L32 6Z" stroke="currentColor" strokeWidth="2"/><path d="m23 32 6 6 13-14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
              </div>
              <h3>让双方知道平台做什么，<br />也知道平台不做什么。</h3>
              <p>平台负责连接与记录。课程安排和酬谢，由双方协商。</p>
            </div>
            <div className={styles.principleList}>
              <article data-reveal><span>01</span><div><h3>隐私设置</h3><p>隐私账号 · 黑名单 · 设备管理</p></div><Arrow diagonal /></article>
              <article data-reveal><span>02</span><div><h3>反馈与申诉</h3><p>遇到问题，可在应用内提交。</p></div><Arrow diagonal /></article>
              <article data-reveal><span>03</span><div><h3>双向评价</h3><p>完成后互评，保留履约记录。</p></div><Arrow diagonal /></article>
              <article data-reveal><span>04</span><div><h3>线下结算</h3><p>平台不代收、不托管酬谢。</p></div><Arrow diagonal /></article>
            </div>
          </div>
        </section>

        <section className={styles.faqSection} id="faq" data-reveal>
          <div className={styles.faqIntro}>
            <div className={styles.eyebrow}><span>QUESTIONS / 04</span><i />常见问题</div>
            <h2>开始之前，<br /><em>先把关键问题说清。</em></h2>
            <p>几件开始前值得知道的事。</p>
            <a href="#download" className={styles.textButton}>继续了解课安 <Arrow /></a>
          </div>
          <div className={styles.faqList}>
            {faqs.map((faq, index) => (
              <article className={`${styles.faqItem} ${activeFaq === index ? styles.faqItemOpen : ""}`} key={faq.question} data-reveal>
                <button type="button" aria-expanded={activeFaq === index} onClick={() => setActiveFaq(activeFaq === index ? null : index)}>
                  <span>{faq.question}</span><i aria-hidden="true">{activeFaq === index ? "−" : "+"}</i>
                </button>
                <div className={styles.faqAnswer} aria-hidden={activeFaq !== index}><p>{faq.answer}</p></div>
              </article>
            ))}
          </div>
        </section>

        <section className={styles.downloadSection} id="download" data-reveal>
          <div className={styles.downloadOrb} aria-hidden="true"><span /><span /><span /></div>
          <div className={styles.eyebrow}><span>KEAN / NEXT STEP</span><i />从一次清楚的沟通开始</div>
          <h2>让临时安排，<br /><em>也有清楚路径。</em></h2>
          <p>从一次清楚的沟通开始。</p>
          <a className={styles.lightButton} href="#product">重新浏览产品能力 <Arrow /></a>
          <span className={styles.releaseNote}>Android 客户端下载信息将在安装包正式提供后更新</span>
        </section>
      </main>

      <footer className={styles.footer} data-reveal>
        <div className={styles.footerMain}>
          <a className={styles.brand} href="#top"><BrandMark /><span>课安</span><small>KEAN</small></a>
          <p>校园代课互助平台<br />让需求与愿意帮忙的人，清楚地连接起来。</p>
          <div className={styles.footerLinks}>
            <a href="#product">产品能力</a><a href="#workflow">协作流程</a><a href="#trust">安全与规则</a><a href="#faq">常见问题</a>
          </div>
        </div>
        <div className={styles.footerBottom}><span>© {new Date().getFullYear()} 课安 KEAN</span><span>酬谢由双方线下协商结算 · 平台提供信息撮合与履约记录</span><a href="#top">回到顶部 ↑</a></div>
      </footer>
    </div>
  );
}
