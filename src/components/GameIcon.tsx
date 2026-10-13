import { gameIcon } from "../market/universe";

type Size = "small" | "default" | "large" | "xl" | "detail" | "table" | "news";

const REPLACEMENT_ICONS: Record<string, string> = {
  AK: "ak-endfield.png",
  OW: "el-overwatch-symbol.svg",
  CRK: "px-cookiekingdom.png",
  TR: "sd-trickcal.png",
  VR: "lt-valorant.png",
  KRP: "mh-kartrush.png",
};

export function GameIcon({
  code,
  name,
  size = "default",
  className = "",
}: {
  code: string;
  name?: string;
  size?: Size;
  className?: string;
}) {
  const replacement = REPLACEMENT_ICONS[code];
  const source = replacement ? `/game-icons/${replacement}` : gameIcon(code);
  const sizeClassName =
    size === "detail"
      ? "game-icon"
      : size === "table"
        ? "game-icon game-icon-table"
        : size === "news"
          ? "game-icon game-icon-news"
          : `game-icon is-${size}`;
  const classes = `${sizeClassName}${code === "OW" ? " is-overwatch-symbol" : ""}${className ? ` ${className}` : ""}`;

  if (!source) return <span className={classes} aria-hidden="true" />;

  return <img className={classes} src={source} alt={name ? `${name} 앱 아이콘` : ""} aria-hidden={name ? undefined : true} loading="lazy" decoding="async" />;
}
