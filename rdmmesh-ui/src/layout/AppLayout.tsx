import { useMemo } from "react";
import { Layout, Menu, Space, Typography, type MenuProps } from "antd";
import { Link, Outlet, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ApiOutlined, AppstoreOutlined, FileSearchOutlined, ProfileOutlined } from "@ant-design/icons";

import { useAuth } from "@/auth/AuthContext";
import { LangSwitcher } from "./LangSwitcher";
import { UserMenu } from "./UserMenu";

const { Header, Sider, Content } = Layout;

export function AppLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const { baseRoles } = useAuth();

  const isAdmin = baseRoles.includes("RDM_ADMIN");

  const selected = pathname.startsWith("/admin/subscriptions")
    ? "subscriptions"
    : pathname.startsWith("/admin/audit")
      ? "audit"
      : pathname.startsWith("/tasks")
        ? "tasks"
        : "catalog";

  const items: MenuProps["items"] = useMemo(() => {
    const main: MenuProps["items"] = [
      {
        key: "catalog",
        icon: <AppstoreOutlined />,
        label: <Link to="/catalog">{t("nav.catalog")}</Link>,
      },
      {
        key: "tasks",
        icon: <ProfileOutlined />,
        label: <Link to="/tasks">{t("nav.tasks")}</Link>,
      },
    ];
    if (!isAdmin) return main;
    return [
      ...main,
      { type: "divider" as const },
      {
        key: "admin-group",
        type: "group" as const,
        label: t("nav.admin"),
        children: [
          {
            key: "subscriptions",
            icon: <ApiOutlined />,
            label: <Link to="/admin/subscriptions">{t("nav.subscriptions")}</Link>,
          },
          {
            key: "audit",
            icon: <FileSearchOutlined />,
            label: <Link to="/admin/audit">{t("nav.audit")}</Link>,
          },
        ],
      },
    ];
  }, [t, isAdmin]);

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Sider
        width={220}
        breakpoint="md"
        theme="light"
        style={{ borderRight: "1px solid #f0f0f0" }}
      >
        <div style={{ padding: "16px 24px" }}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {t("app.title")}
          </Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t("app.subtitle")}
          </Typography.Text>
        </div>
        <Menu mode="inline" selectedKeys={[selected]} items={items} />
      </Sider>
      <Layout>
        <Header
          style={{
            background: "#fff",
            display: "flex",
            justifyContent: "flex-end",
            alignItems: "center",
            padding: "0 24px",
            borderBottom: "1px solid #f0f0f0",
          }}
        >
          <Space size="middle">
            <LangSwitcher />
            <UserMenu />
          </Space>
        </Header>
        <Content style={{ padding: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
