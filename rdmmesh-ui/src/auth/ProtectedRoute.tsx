import { useEffect, type ReactNode } from "react";
import { Spin } from "antd";
import { useAuth } from "./AuthContext";

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { ready, user, login } = useAuth();

  useEffect(() => {
    if (ready && !user) {
      void login();
    }
  }, [ready, user, login]);

  if (!ready || !user) {
    return (
      <div style={{ display: "grid", placeItems: "center", height: "100vh" }}>
        <Spin size="large" />
      </div>
    );
  }
  return <>{children}</>;
}
