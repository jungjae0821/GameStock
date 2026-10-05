/** 옆에 워드마크가 있으므로 이미지 자체는 장식으로 둔다. */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <img
      className="brand-mark"
      src="/brand/shuen-mark.png"
      width={size}
      height={size}
      alt=""
      decoding="async"
      fetchPriority="high"
    />
  );
}
