import React, { useEffect, useRef, useState } from 'react';
import { Image, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../auth';
import { featureLock, useCareer, useEngagement } from '../engagement';
import { won } from '../format';
import { DESK_SPEECH, OJT_HINT, PRIORITY, fill, introSpeech, onScene, queueScene, readMentor, sceneForEvent, writeMentor } from '../mentor';
import { RADIUS, RADIUS_XS, SPACE, Txt, useTheme } from '../theme';

// 사수를 자처한 슈엔 사장. 웹 src/next/mentor/*를 앱으로 옮겼다.
// 앱 안 웹 화면(app-shell)은 이 일을 앱에 맡기고 첫 출근·발령·갈구기를 띄우지 않는다.

const MARK = require('../../assets/brand/shuen-mark.png');
const KICKER = { info: '슈엔 사장', praise: '슈엔 사장 · 발령', scold: '슈엔 사장 · 호출' };

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

/** 한 장씩 말하는 대화창. 마지막 장에서 닫거나 다음 화면으로 보낸다. */
function MentorDialog({ scene, onClose }) {
  const theme = useTheme();
  const [page, setPage] = useState(0);
  const { tone, title, pages, certificate, primary } = scene;
  const last = page >= pages.length - 1;
  const scold = tone === 'scold';
  const dismissLabel = scold ? '죄송합니다, 사장님' : '네, 사장님';

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
            <View style={styles.speaker}>
              <Image source={MARK} style={[styles.avatar, { backgroundColor: theme.raised }]} resizeMode="contain" accessibilityIgnoresInvertColors />
              <View style={styles.grow}>
                <Txt variant="caption" style={{ color: scold ? theme.guardText : theme.inkSoft }}>{KICKER[tone]}</Txt>
                <Txt variant="headline" accessibilityRole="header">{title}</Txt>
              </View>
            </View>
            <View style={[styles.line, { backgroundColor: scold ? theme.guardWash : theme.raised }]}>
              <Txt variant="body" accessibilityLiveRegion="polite">{pages[page]}</Txt>
            </View>
            {pages.length > 1 ? (
              <View style={styles.progress} accessible accessibilityLabel={`${pages.length}장 중 ${page + 1}장`}>
                {pages.map((_, index) => <View key={index} style={[styles.dot, { backgroundColor: index === page ? theme.ink : theme.line }]} />)}
              </View>
            ) : null}
            <View style={styles.actions}>
              {page > 0 ? <DialogButton label="이전" onPress={() => setPage(page - 1)} /> : null}
              {!last ? <DialogButton strong label="다음" onPress={() => setPage(page + 1)} /> : (
                <>
                  {primary ? <DialogButton label={dismissLabel} onPress={() => onClose()} /> : null}
                  <DialogButton strong label={primary?.label ?? dismissLabel} onPress={() => onClose(primary?.to)} />
                </>
              )}
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
      style={({ pressed }) => [styles.button, { backgroundColor: strong ? theme.ink : theme.raised }, pressed && { opacity: 0.85 }]}
    >
      <Txt variant="callout" style={{ color: strong ? theme.ground : theme.ink }}>{label}</Txt>
    </Pressable>
  );
}

/** OJT 동안 홈에 붙는 진행 카드. 다음 할 일 한 줄과 커리어로 가는 버튼. */
export function OjtCard({ onOpenCareer }) {
  const theme = useTheme();
  const career = useCareer();
  const [hidden, setHidden] = useState(false);
  if (hidden || !career || career.legacy || career.exempt || career.grade !== 'TRAINEE') return null;
  const steps = career.promotion ?? [];
  const next = steps.find(step => !step.done);
  if (!next) return null;
  const done = steps.filter(step => step.done).length;
  return (
    <View style={[styles.ojt, { backgroundColor: theme.card }]}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`OJT ${done}/${steps.length}, 슈엔 사장: ${OJT_HINT[next.id] ?? next.label}. 내 커리어에서 이어서 하기`}
        onPress={onOpenCareer}
        style={({ pressed }) => [styles.ojtBody, pressed && { backgroundColor: theme.pressed }]}
      >
        <Image source={MARK} style={[styles.avatarSmall, { backgroundColor: theme.raised }]} resizeMode="contain" accessibilityIgnoresInvertColors />
        <View style={styles.grow}>
          <Txt variant="micro" tone="inkSoft" numeric>{`OJT ${done}/${steps.length} · 슈엔 사장`}</Txt>
          <Txt variant="caption">{OJT_HINT[next.id] ?? next.label}</Txt>
        </View>
        <Ionicons name="chevron-forward" size={16} color={theme.inkSoft} />
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
  speaker: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  avatar: { width: 48, height: 48, borderRadius: 24 },
  avatarSmall: { width: 32, height: 32, borderRadius: 16 },
  line: { borderRadius: RADIUS, paddingHorizontal: SPACE.lg, paddingVertical: SPACE.md },
  progress: { flexDirection: 'row', justifyContent: 'center', gap: 6 },
  dot: { width: 6, height: 6, borderRadius: 3 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', flexWrap: 'wrap', gap: SPACE.sm },
  button: { minHeight: 44, borderRadius: RADIUS, paddingHorizontal: SPACE.lg, alignItems: 'center', justifyContent: 'center' },
  ojt: { flexDirection: 'row', alignItems: 'center', borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  ojtBody: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: SPACE.md, paddingVertical: SPACE.md, paddingLeft: SPACE.lg + 2, paddingRight: SPACE.sm },
  ojtClose: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', marginRight: SPACE.xs },
});
