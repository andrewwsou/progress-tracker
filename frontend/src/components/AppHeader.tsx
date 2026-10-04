import { LogoMark } from "./Icons";

export function AppHeader({ onSignOut }: { onSignOut: () => void }) {
  return (
    <header className="topbar">
      <div className="topbar__inner">
        <div className="brand">
          <LogoMark />
          <span>ProgressArc</span>
        </div>
        <button className="button button--quiet" type="button" onClick={onSignOut}>
          Sign out
        </button>
      </div>
    </header>
  );
}
