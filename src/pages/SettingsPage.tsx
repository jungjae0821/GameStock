import { AccountSettings } from "./AccountSettings";

export function SettingsPage() {
  return (
    <div className="page-stack">
      <h1 className="page-title">설정</h1>
      <AccountSettings />
    </div>
  );
}
