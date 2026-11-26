import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, Image, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../auth';
import { featureLock, useCareer, useEngagement } from '../engagement';
import { won } from '../format';
import { DESK_SPEECH, OJT_HINT, PRIORITY, fill, introSpeech, onScene, queueScene, readMentor, sceneForEvent, writeMentor } from '../mentor';
import { RADIUS, RADIUS_XS, SPACE, Txt, useTheme } from '../theme';

// 사수를 자처한 슈엔 사장. 웹 src/next/mentor/*를 앱으로 옮겼다.
// 앱 안 웹 화면(app-shell)은 이 일을 앱에 맡기고 첫 출근·발령·갈구기를 띄우지 않는다.

// 웹 public/characters/shuen.webp와 같은 전신 그림(815×1660). 키워서 위쪽만 보이게 자른다.
const SHUEN = require('../../assets/characters/shuen.webp');
const SHUEN_RATIO = 1660 / 815;
const KICKER = { info: '', praise: '발령', scold: '호출' };

/**
 * 슈엔 상반신. 웹 MentorPortrait처럼 전신 그림을 scale배로 키워 가운데에 두고 위에서부터 보이게 자른다.
 * 웹 휴대폰 화면의 큰 대화창(132×128, 180%)과 OJT 작은 대화창(170%, 위 6px)을 앱 크기에 맞췄다.
 */
function ShuenPortrait({ width, height, scale, top = 0 }) {
  const imageWidth = width * scale;
  return (
    <View style={{ width, height, overflow: 'hidden' }} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <Image source={SHUEN} accessibilityIgnoresInvertColors
        style={{ position: 'absolute', top, left: (width - imageWidth) / 2, width: imageWidth, height: imageWidth * SHUEN_RATIO }} />
    </View>
  );
}

/** 대사를 한 글자씩 보여 준다. 움직임 줄이기를 켰으면 바로 다 보여 준다. 웹 useTypewriter와 같다. */
function useTypewriter(text, speedMs = 26) {
  const letters = Array.from(text);
  const total = letters.length;
  const [count, setCount] = useState(0);
  const [reduce, setReduce] = useState(false);
  const [source, setSource] = useState(text);
  if (source !== text) {
    setSource(text);
    setCount(0);
  }
  useEffect(() => {
    let alive = true;
    AccessibilityInfo.isReduceMotionEnabled().then(value => { if (alive) setReduce(value); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  const shownCount = reduce ? total : count;
  useEffect(() => {
    if (shownCount >= total) return undefined;
    const timer = setTimeout(() => setCount(current => Math.min(total, current + 1)), speedMs);
    return () => clearTimeout(timer);
  }, [shownCount, total, speedMs]);
  return { shown: letters.slice(0, shownCount).join(''), done: shownCount >= total, skip: () => setCount(total) };
}

/** 말상자에 걸치는 이름표. 웹 .mentor-nameplate처럼 진한 바탕에 "슈엔 사장"과 꼬리표를 둔다. 갈굴 때는 주의색이다. */
function Nameplate({ tag, scold, small }) {
  const theme = useTheme();
  const back = scold ? theme.guard : theme.ink;
  const fore = scold ? '#12101C' : theme.ground;
  const variant = small ? 'micro' : 'caption';
  return (
    <View style={[styles.nameplate, small && styles.nameplateSmall, { backgroundColor: back }]}>
      <Txt variant={variant} style={{ color: fore, fontWeight: '700' }}>슈엔 사장</Txt>
      {tag ? <Txt variant={variant} numeric style={[styles.nameplateTag, { color: fore, borderLeftColor: fore }]}>{tag}</Txt> : null}
    </View>
  );
}

/**
 * 커리어 기록을 보고 첫 출근 안내, 승진 발령장, 강등·재교육·손실한도 호출을 한 번씩 띄운다.
 * blocked인 동안(로그인·공지 창이 떠 있을 때)은 줄에 세워 두었다가 닫힌 뒤에 띄운다. iOS는 창을 겹쳐 열지 못한다.
 */
export function MentorHost({ blocked, onGo }) {
  const { user, profile } = useAuth();
  const career = useCareer();
  const [queue, setQueue] = useState([]);
  const chain = useRef(Promise.resolve());
  const uid = user?.uid ?? null;
  const nick = profile?.nickname || '신입';
  const nickRef = useRef(nick);
  nickRef.current = nick;

  useEffect(() => onScene(scene => setQueue(current => [...current, { ...scene, id: `${Date.now()}:${Math.random()}` }])), []);
  // 계정이 바뀌면 이전 사람에게 줄 장면은 버린다.
  useEffect(() => { setQueue([]); }, [uid]);

  useEffect(() => {
    if (!uid || !career || career.exempt || career.legacy) return;
    // 커리어는 1분마다 다시 읽힌다. 기억을 읽고 쓰는 동안 다음 확인이 끼어들어 같은 장면을 두 번 세우지 않게 차례로 돈다.
    chain.current = chain.current.then(async () => {
      const saved = await readMentor(uid);
      const events = career.events ?? [];
      const newest = events[0]?.at ?? null;
      if (!saved.intro && career.grade === 'TRAINEE') {
        writeMentor(uid, { ...saved, intro: true, lastEventAt: newest });
        const speech = introSpeech(events.some(event => event.kind === 'MIGRATE'));
        queueScene({ tone: 'info', title: speech.title, pages: speech.pages.map(text => fill(text, { nick: nickRef.current })), primary: { label: 'OJT 시작하기', to: '/career' } });
        return;
      }
      if (saved.lastEventAt === undefined) {
        // 이 기기에서 처음 보는 사람이면 지난 기록을 다시 틀지 않고 지금부터 센다.
        writeMentor(uid, { ...saved, intro: true, lastEventAt: newest });
        return;
      }
      const fresh = events.filter(event => !saved.lastEventAt || event.at > saved.lastEventAt);
      if (!fresh.length) return;
      writeMentor(uid, { ...saved, lastEventAt: newest });
      const top = [...fresh].sort((left, right) => (PRIORITY[right.kind] ?? 0) - (PRIORITY[left.kind] ?? 0))[0];
      const next = sceneForEvent(top, nickRef.current);
      if (next) queueScene(next);
    }).catch(() => {});
  }, [uid, career]);

  const scene = queue[0] ?? null;
  const close = to => {
    setQueue(current => current.slice(1));
    if (to) onGo(to);
  };
  if (!scene || blocked) return null;
  return <MentorDialog key={scene.id} scene={scene} onClose={close} />;
}

/**
 * 한 장씩 말하는 대화창. 웹 MentorDialog의 휴대폰 모양처럼 위에 슈엔 상반신, 아래 말상자에 대사가 한 글자씩 찍힌다.
 * 글자가 다 나오기 전에 "다음"을 누르거나 대사를 누르면 남은 글자를 먼저 채운다.
 */
function MentorDialog({ scene, onClose }) {
  const theme = useTheme();
  const [page, setPage] = useState(0);
  const { tone, title, pages, certificate, primary } = scene;
  const text = pages[page] ?? '';
  const typing = useTypewriter(text);
  const last = page >= pages.length - 1;
  const scold = tone === 'scold';
  const dismissLabel = scold ? '죄송합니다, 사장님' : '네, 사장님';
  const boxColor = scold ? theme.guardWash : theme.raised;
  const next = () => (typing.done ? setPage(page + 1) : typing.skip());

  return (
    <Modal visible transparent animationType="fade" onRequestClose={() => onClose()} statusBarTranslucent>
      <View style={[styles.scrim, { backgroundColor: theme.scrim }]}>
        <View
          style={[styles.card, { backgroundColor: theme.card }, scold && { borderTopWidth: 6, borderTopColor: theme.guard }]}
          accessibilityViewIsModal
        >
          <ScrollView contentContainerStyle={styles.cardBody} bounces={false}>
            {certificate ? (
              <View style={[styles.certificate, { borderColor: theme.ink }]} accessible accessibilityLabel={`발령장. 성명 ${certificate.nickname}, 직위 ${certificate.gradeLabel}, 운용한도 ${won(certificate.allocation)}. ${certificate.date}, 슈엔증권 대표이사 슈엔`}>
                <Txt variant="title" style={styles.certificateHead}>발 령</Txt>
                <CertificateLine label="성명" value={certificate.nickname} />
                <CertificateLine label="직위" value={certificate.gradeLabel} />
                <CertificateLine label="운용한도" value={won(certificate.allocation)} numeric />
                <Txt variant="callout" style={styles.certificateBody}>{`위 사람을 ${certificate.gradeLabel}(으)로 발령함.`}</Txt>
                <Txt variant="micro" tone="inkSoft">{`${certificate.date} · 슈엔증권 대표이사 슈엔`}</Txt>
              </View>
            ) : null}
            <View>
              <View style={styles.bust}>
                <ShuenPortrait width={132} height={128} scale={1.8} />
              </View>
              <View style={[styles.box, { backgroundColor: boxColor }]}>
                <View style={[styles.boxTail, { backgroundColor: boxColor }]} />
                <Nameplate tag={KICKER[tone]} scold={scold} />
                <Txt variant="headline" accessibilityRole="header">{title}</Txt>
                <Pressable onPress={typing.skip} accessible accessibilityLabel={text} accessibilityLiveRegion="polite">
                  <Txt variant="body" style={styles.line}>{typing.shown}{typing.done ? '' : '▍'}</Txt>
                </Pressable>
                {pages.length > 1 ? (
                  <View style={styles.progress} accessible accessibilityLabel={`${pages.length}장 중 ${page + 1}장`}>
                    {pages.map((_, index) => <View key={index} style={[styles.dot, { backgroundColor: index === page ? theme.ink : theme.line }]} />)}
                  </View>
                ) : null}
                <View style={styles.actions}>
                  {page > 0 ? <DialogButton label="이전" onPress={() => setPage(page - 1)} /> : null}
                  {!last || !typing.done ? <DialogButton strong label="다음" onPress={next} /> : (
                    <>
                      {primary ? <DialogButton label={dismissLabel} onPress={() => onClose()} /> : null}
                      <DialogButton strong label={primary?.label ?? dismissLabel} onPress={() => onClose(primary?.to)} />
                    </>
                  )}
                </View>
              </View>
            </View>
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

function CertificateLine({ label, value, numeric }) {
  return (
    <View style={styles.certificateLine}>
      <Txt variant="callout" tone="inkSoft" style={styles.certificateLabel}>{label}</Txt>
      <Txt variant="callout" numeric={numeric} style={styles.grow}>{value}</Txt>
    </View>
  );
}

function DialogButton({ label, onPress, strong }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.button, { backgroundColor: strong ? theme.ink : theme.card }, pressed && { opacity: 0.85 }]}
    >
      <Txt variant="callout" style={{ color: strong ? theme.ground : theme.ink }}>{label}</Txt>
    </Pressable>
  );
}

/**
 * OJT 동안 홈에 붙는 작은 대화창. 웹 .mentor-ojt를 홈 카드 자리에 맞춘 모양으로, 왼쪽에 슈엔 상반신, 오른쪽 말상자에 다음 할 일.
 * 누르면 내 커리어로 간다.
 */
export function OjtCard({ onOpenCareer }) {
  const theme = useTheme();
  const career = useCareer();
  const [hidden, setHidden] = useState(false);
  const steps = career?.promotion ?? [];
  const next = steps.find(step => !step.done);
  const line = next ? OJT_HINT[next.id] ?? next.label : '';
  const typing = useTypewriter(line);
  if (hidden || !career || career.legacy || career.exempt || career.grade !== 'TRAINEE' || !next) return null;
  const done = steps.filter(step => step.done).length;
  return (
    <View style={[styles.ojt, { backgroundColor: theme.card }]}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`OJT ${done}/${steps.length}, 슈엔 사장: ${line}. 내 커리어에서 이어서 하기`}
        onPress={onOpenCareer}
        style={({ pressed }) => [styles.ojtBody, pressed && { backgroundColor: theme.pressed }]}
      >
        <ShuenPortrait width={88} height={116} scale={1.7} top={6} />
        <View style={[styles.ojtBox, { backgroundColor: theme.raised }]}>
          <View style={[styles.ojtTail, { backgroundColor: theme.raised }]} />
          <Nameplate small tag={`OJT ${done}/${steps.length}`} />
          <Txt variant="callout" style={styles.ojtLine}>{typing.shown}{typing.done ? '' : '▍'}</Txt>
          <View style={styles.ojtMore}>
            <Txt variant="caption" tone="inkSoft">이어서</Txt>
            <Ionicons name="chevron-forward" size={14} color={theme.inkSoft} />
          </View>
        </View>
      </Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="OJT 안내 숨기기" onPress={() => setHidden(true)} style={styles.ojtClose} hitSlop={4}>
        <Ionicons name="close" size={18} color={theme.inkSoft} />
      </Pressable>
    </View>
  );
}

/**
 * 열린 데스크(선물·옵션)에 처음 들어오면 슈엔이 핵심만 짚어 준다. 한 번 보면 다시 뜨지 않고,
 * 이전을 기다리는 기존 회원처럼 원래 쓰던 사람에게는 띄우지 않는다.
 */
export function useDeskIntro(desk) {
  const { user } = useAuth();
  const career = useCareer();
  const engagement = useEngagement();
  const uid = user?.uid ?? null;
  const unlocked = engagement !== null && featureLock(engagement, desk) === null;
  const ready = Boolean(uid && career && !career.legacy && !career.exempt && unlocked);
  useEffect(() => {
    if (!ready) return undefined;
    let cancelled = false;
    void readMentor(uid).then(saved => {
      if (cancelled || saved.desks?.includes(desk)) return;
      writeMentor(uid, { ...saved, desks: [...(saved.desks ?? []), desk] });
      const speech = DESK_SPEECH[desk];
      if (speech) queueScene({ tone: 'info', title: speech.title, pages: speech.pages });
    });
    return () => { cancelled = true; };
  }, [ready, uid, desk]);
}

const styles = StyleSheet.create({
  grow: { flex: 1, minWidth: 0 },
  scrim: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: SPACE.lg },
  card: { width: '100%', maxWidth: 460, maxHeight: '90%', borderRadius: RADIUS, overflow: 'hidden' },
  cardBody: { padding: SPACE.xl, gap: SPACE.lg },
  certificate: { borderWidth: 3, borderStyle: 'solid', borderRadius: RADIUS_XS, padding: SPACE.lg, alignItems: 'center', gap: 4 },
  certificateHead: { letterSpacing: 8, marginBottom: SPACE.sm },
  certificateLine: { flexDirection: 'row', gap: SPACE.md, width: '100%', maxWidth: 260 },
  certificateLabel: { width: 64 },
  certificateBody: { marginTop: SPACE.sm },
  // 상반신 아래쪽은 말상자가 덮어서, 슈엔이 말상자 뒤에 서 있는 것처럼 보인다.
  bust: { marginLeft: SPACE.sm, marginBottom: -SPACE.xl },
  box: { borderRadius: RADIUS, paddingHorizontal: SPACE.lg, paddingTop: SPACE.xl, paddingBottom: SPACE.lg, gap: SPACE.md },
  boxTail: { position: 'absolute', top: -7, left: 64, width: 14, height: 14, transform: [{ rotate: '45deg' }] },
  nameplate: {
    position: 'absolute', top: -14, right: SPACE.lg, flexDirection: 'row', alignItems: 'center', gap: SPACE.sm,
    borderRadius: RADIUS_XS, paddingHorizontal: SPACE.md, paddingVertical: 4,
  },
  nameplateSmall: { top: -11, left: SPACE.md, right: undefined, paddingHorizontal: SPACE.sm, paddingVertical: 2 },
  nameplateTag: { paddingLeft: SPACE.sm, borderLeftWidth: StyleSheet.hairlineWidth, fontWeight: '600' },
  line: { minHeight: 26 * 4 },
  progress: { flexDirection: 'row', justifyContent: 'center', gap: 6 },
  dot: { width: 6, height: 6, borderRadius: 3 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', flexWrap: 'wrap', gap: SPACE.sm },
  button: { minHeight: 44, borderRadius: RADIUS, paddingHorizontal: SPACE.lg, alignItems: 'center', justifyContent: 'center' },
  ojt: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  ojtBody: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACE.xs, paddingTop: SPACE.md, paddingRight: SPACE.md, paddingLeft: SPACE.sm },
  ojtBox: { flex: 1, minWidth: 0, marginTop: SPACE.xl, marginBottom: SPACE.md, borderRadius: RADIUS, paddingHorizontal: SPACE.md, paddingTop: SPACE.lg, paddingBottom: SPACE.sm, gap: SPACE.xs },
  ojtTail: { position: 'absolute', left: -6, bottom: 28, width: 12, height: 12, transform: [{ rotate: '45deg' }] },
  ojtLine: { minHeight: 22 * 2 },
  ojtMore: { flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-end', gap: 2 },
  ojtClose: { position: 'absolute', top: 0, right: 0, width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
});
