import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, Spin } from "antd";
import { userManager } from "./oidc";

export function Callback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    userManager
      .signinRedirectCallback()
      .then(() => {
        if (!cancelled) navigate("/", { replace: true });
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (error) {
    return (
      <div style={{ padding: 24 }}>
        <Alert type="error" message="Ошибка входа" description={error} showIcon />
      </div>
    );
  }
  return (
    <div style={{ display: "grid", placeItems: "center", height: "100vh" }}>
      <Spin tip="Завершаем вход..." size="large" />
    </div>
  );
}
