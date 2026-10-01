const ICON_BY_CODE: Record<string, string> = {
  // Official app icons sourced from each game's Google Play listing.
  GOV: "/game-icons/gov.png",
  UMA: "/game-icons/uma.png",
  BA: "/game-icons/ba.png",
  ZZZ: "/game-icons/zzz.png",
};

export function GameIcon({
  code,
  name,
  size = "detail",
}: {
  code: string;
  name: string;
  size?: "detail" | "table" | "news";
}) {
  const source = ICON_BY_CODE[code];
  const className = `game-icon${size !== "detail" ? ` game-icon-${size}` : ""}`;

  if (!source) {
    return (
      <span className={`${className} game-icon-fallback`} aria-hidden="true">
        {code}
      </span>
    );
  }

  return <img className={className} src={source} alt={`${name} 앱 아이콘`} />;
}
