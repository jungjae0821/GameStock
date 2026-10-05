import { NewsFeed } from "../components/NewsFeed";

export function NewsPage() {
  return (
    <div className="page-stack is-compact">
      <h1 className="page-title">속보</h1>
      <NewsFeed />
    </div>
  );
}
