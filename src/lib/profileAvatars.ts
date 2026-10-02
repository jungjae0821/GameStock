export type ProfileAvatar = {
  id: string;
  name: string;
  description: string;
  src: string;
};

/**
 * 랭킹에서 작은 크기로도 식별하기 쉬운 오리지널 게임 마스코트 프사.
 * 정적 자산 경로만 저장하므로 로그인 제공자의 외부 프로필 사진과도 구분된다.
 */
export const PROFILE_AVATARS: readonly ProfileAvatar[] = [
  {
    id: "mint-hood",
    name: "민트 후드",
    description: "차분하게 준비하는 마스코트",
    src: "/profile-avatars/mint-hood-384.png",
  },
  {
    id: "coral-scarf",
    name: "코랄 스카프",
    description: "기세 좋게 출발하는 마스코트",
    src: "/profile-avatars/coral-scarf-384.png",
  },
  {
    id: "cobalt-visor",
    name: "코발트 바이저",
    description: "시세를 집중해서 보는 마스코트",
    src: "/profile-avatars/cobalt-visor-384.png",
  },
  {
    id: "golden-headband",
    name: "골든 밴드",
    description: "자신 있게 도전하는 마스코트",
    src: "/profile-avatars/golden-headband-384.png",
  },
  {
    id: "violet-hood",
    name: "바이올렛 후드",
    description: "조용히 흐름을 읽는 마스코트",
    src: "/profile-avatars/violet-hood-384.png",
  },
  {
    id: "teal-leaf",
    name: "틸 리프",
    description: "꾸준히 모아가는 마스코트",
    src: "/profile-avatars/teal-leaf-384.png",
  },
  {
    id: "rose-earmuffs",
    name: "로즈 이어머프",
    description: "따뜻하게 응원하는 마스코트",
    src: "/profile-avatars/rose-earmuffs-384.png",
  },
];

export const DEFAULT_PROFILE_AVATAR = PROFILE_AVATARS[0].src;

export function isProfileAvatar(value: string | null | undefined): boolean {
  return Boolean(value && PROFILE_AVATARS.some((avatar) => avatar.src === value));
}
