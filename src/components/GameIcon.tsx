const ICON_BY_CODE: Record<string, string> = {
  // Official app icons sourced from each game's Google Play listing.
  GOV: "/game-icons/gov.png",
  UMA: "/game-icons/uma.png",
  BA: "/game-icons/ba.png",
};

export function GameIcon({
  code,
  name,
  size = "detail",
}: {
  code: string;
  name: string;
  size?: "detail" | "table";
}) {
  const source = ICON_BY_CODE[code];
  const className = `game-icon${size === "table" ? " game-icon-table" : ""}`;

  if (!source) {
    return (
      <span className={`${className} game-icon-fallback`} aria-hidden="true">
        {code}
      </span>
    );
  }

  return <img className={className} src={source} alt={`${name} 앱 아이콘`} />;
}
