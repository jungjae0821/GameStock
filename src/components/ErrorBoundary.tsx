import { Component, type ErrorInfo, type ReactNode } from "react";

type Props = {
  children: ReactNode;
  /** 이 값이 바뀌면(예: 다른 화면으로 이동) 오류 상태를 풀고 다시 그린다. */
  resetKey?: string;
  /** 오류가 났을 때 대신 그릴 것. 주지 않으면 기본 안내 화면을 그린다. null이면 아무것도 그리지 않는다. */
  fallback?: ReactNode | ((retry: () => void) => ReactNode);
  /** 콘솔 로그에 붙는 위치 이름. */
  area: string;
};

type State = { error: Error | null; resetKey?: string };

/**
 * 렌더링 중 예외가 나면 React는 트리 전체를 지운다. 경계를 두어 고장 난 부분만 안내 화면으로 바꾸고
 * 상단 메뉴·탭바처럼 나머지는 계속 쓸 수 있게 한다.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, resetKey: this.props.resetKey };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    if (props.resetKey === state.resetKey) return null;
    return { error: null, resetKey: props.resetKey };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(`[ErrorBoundary:${this.props.area}]`, error, info.componentStack);
  }

  private retry = () => this.setState({ error: null });

  render() {
    if (!this.state.error) return this.props.children;
    const { fallback } = this.props;
    if (typeof fallback === "function") return fallback(this.retry);
    if (fallback !== undefined) return fallback;
    return <ScreenErrorFallback onRetry={this.retry} />;
  }
}

export function ScreenErrorFallback({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="nx-gate" role="alert" data-testid="screen-error">
      <h1 className="nx-page-title">이 화면을 불러오지 못했어요</h1>
      <p className="nx-screen-sub">
        화면을 그리는 중에 문제가 생겼어요. 계좌와 주문 내역은 서버에 그대로 있어요. 다시 시도해도 같으면 다른 화면으로 옮겨 주세요.
      </p>
      <div className="nx-gate-actions">
        <button type="button" className="nx-btn is-primary" onClick={onRetry}>다시 시도</button>
        <a className="nx-btn" href="/">홈으로</a>
      </div>
    </section>
  );
}

/** 앱 전체가 멈췄을 때. 상단 메뉴도 못 그리는 상황이라 새로고침만 권한다. */
export function AppErrorFallback() {
  return (
    <main className="content nx-content nx-page" role="alert" data-testid="app-error">
      <section className="nx-gate">
        <h1 className="nx-page-title">슈엔증권을 여는 중에 문제가 생겼어요</h1>
        <p className="nx-screen-sub">새로고침하면 대부분 해결돼요. 계좌와 주문 내역은 서버에 그대로 있어요.</p>
        <div className="nx-gate-actions">
          <button type="button" className="nx-btn is-primary" onClick={() => window.location.reload()}>새로고침</button>
        </div>
      </section>
    </main>
  );
}
