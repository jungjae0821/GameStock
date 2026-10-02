type Size = "small" | "default" | "large" | "xl" | "detail" | "table" | "news";
const REPLACEMENT_ICONS: Record<string, string> = {
  AK: "ak-endfield.png",
  EL: "el-overwatch-symbol.svg",
  PX: "px-cookiekingdom.png",
  SD: "sd-trickcal.png",
  LT: "lt-valorant.png",
  MH: "mh-kartrush.png",
};
export function GameIcon({ code, name: _name, size = "default", className = "" }: { code: string; name?: string; size?: Size; className?: string }) {
  const mappedSize = size === "table" || size === "detail" ? "default" : size === "news" ? "large" : size;
  const iconCode = code === "GOV" ? "nk" : code.toLowerCase();
  const iconFile = REPLACEMENT_ICONS[code] ?? `${iconCode}.jpg`;
  return <img src={`/game-icons/${iconFile}`} alt="" aria-hidden="true" className={`game-icon is-${mappedSize}${code === "EL" ? " is-overwatch-symbol" : ""}${className ? ` ${className}` : ""}`} loading="lazy" decoding="async" />;
}
