import styles from "./Footer.module.css";

export function Footer() {
  return (
    <footer id="about" className={styles.foot}>
      <p>课安是校园临时代课互助平台，让需要代课的同学和可以代课的同学彼此看见</p>
      <p>我们只做撮合与履约记录，酬谢自行结算</p>
      <p className={styles.copy}>课安</p>
    </footer>
  );
}
