type Props = {
  opacity?: number;
};

export function CampusSilhouette({ opacity = 0.2 }: Props) {
  return (
    <g opacity={opacity} fill="none" stroke="rgba(168, 208, 255, 0.5)" strokeWidth="1">
      <path d="M8 168 V96 H36 V168" />
      <path d="M8 96 L22 70 L36 96" />
      <rect x="44" y="118" width="28" height="50" />
      <rect x="78" y="88" width="22" height="80" />
      <path d="M108 168 V78 H148 V168" />
      <path d="M108 78 L128 52 L148 78" />
      <rect x="156" y="108" width="36" height="60" />
      <rect x="200" y="84" width="28" height="84" />
      <path d="M236 168 V64 H288 V168" />
      <path d="M246 88 H278 M246 108 H278 M246 128 H278" opacity="0.45" />
      <rect x="296" y="118" width="52" height="50" />
      <path d="M296 118 L322 92 L348 118" />
      <rect x="356" y="96" width="22" height="72" />
      <rect x="386" y="72" width="40" height="96" />
      <path d="M386 72 L406 48 L426 72" />
      <rect x="434" y="124" width="64" height="44" />
      <path d="M506 168 V88 H548 V168" />
      <path d="M516 112 H538 M516 132 H538" opacity="0.4" />
      <rect x="556" y="102" width="30" height="66" />
      <path d="M592 168 V76 H640 V168" />
      <path d="M592 76 L616 54 L640 76" />
      <rect x="648" y="120" width="48" height="48" />
      <rect x="704" y="90" width="24" height="78" />
      <path d="M736 168 V70 H790 V168" />
      <path d="M748 98 H778 M748 122 H778" opacity="0.4" />
      <line x1="36" y1="152" x2="44" y2="152" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="72" y1="150" x2="78" y2="150" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="100" y1="156" x2="108" y2="156" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="148" y1="150" x2="156" y2="150" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="192" y1="148" x2="200" y2="148" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="228" y1="152" x2="236" y2="152" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="288" y1="150" x2="296" y2="150" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="348" y1="146" x2="356" y2="146" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="426" y1="150" x2="434" y2="150" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="498" y1="154" x2="506" y2="154" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="548" y1="150" x2="556" y2="150" stroke="rgba(126, 182, 255, 0.28)" />
      <line x1="640" y1="148" x2="648" y2="148" stroke="rgba(126, 182, 255, 0.28)" />
    </g>
  );
}
