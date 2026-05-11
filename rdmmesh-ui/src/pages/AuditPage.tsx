import { useState } from "react";
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  type TableColumnsType,
} from "antd";
import { ClearOutlined, SearchOutlined } from "@ant-design/icons";
import dayjs, { type Dayjs } from "dayjs";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";

import { api, type AuditQuery } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";
import { useAuth } from "@/auth/AuthContext";
import type { AuditEntry } from "@/api/types";

// Известные event_type'ы — из AuditEventClassifier (E10 §1.4). Backend может слать
// и неизвестные ("simpleClassName" fallback) — Select допускает any string через
// mode="tags".
const KNOWN_EVENT_TYPES = [
  "WORKFLOW_TRANSITION",
  "VERSION_PUBLISHED",
  "OWNERSHIP_CHANGED",
];

const KNOWN_AGGREGATE_TYPES = ["VERSION", "DOMAIN", "CODESET"];

// Цвета для event_type в Tag — синхронно с workflow flow (event = workflow ⇒ blue).
const EVENT_COLOR: Record<string, string> = {
  WORKFLOW_TRANSITION: "blue",
  VERSION_PUBLISHED: "green",
  OWNERSHIP_CHANGED: "purple",
};

interface FormValues {
  eventType?: string | null;
  aggregateType?: string | null;
  aggregateId?: string | null;
  actor?: string | null;
  range?: [Dayjs | null, Dayjs | null] | null;
  q?: string | null;
}

export function AuditPage() {
  const { t } = useTranslation();
  const { baseRoles } = useAuth();
  const isAdmin = baseRoles.includes("RDM_ADMIN");

  const [form] = Form.useForm<FormValues>();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [filters, setFilters] = useState<AuditQuery>({});

  const q = useQuery({
    queryKey: qk.audit.list(page, size, {
      eventType: filters.eventType ?? null,
      aggregateType: filters.aggregateType ?? null,
      aggregateId: filters.aggregateId ?? null,
      actor: filters.actor ?? null,
      from: filters.from ?? null,
      to: filters.to ?? null,
      q: filters.q ?? null,
    }),
    queryFn: () => api.listAuditEntries(page, size, filters),
    enabled: isAdmin,
  });

  if (!isAdmin) {
    return (
      <Alert
        type="warning"
        showIcon
        message={t("audit.notAdminTitle")}
        description={t("audit.notAdminDescription")}
      />
    );
  }

  const applyFilters = () => {
    const v = form.getFieldsValue();
    const next: AuditQuery = {
      eventType: trim(v.eventType),
      aggregateType: trim(v.aggregateType),
      aggregateId: trim(v.aggregateId),
      actor: trim(v.actor),
      from: v.range?.[0]?.toISOString() ?? null,
      to: v.range?.[1]?.toISOString() ?? null,
      q: trim(v.q),
    };
    setPage(0);
    setFilters(next);
  };

  const clearFilters = () => {
    form.resetFields();
    setPage(0);
    setFilters({});
  };

  const columns: TableColumnsType<AuditEntry> = [
    {
      title: t("audit.col.occurredAt"),
      dataIndex: "occurred_at",
      key: "occurred_at",
      width: 200,
      render: (v: string) => (
        <Typography.Text style={{ fontSize: 12, fontFamily: "monospace" }}>
          {dayjs(v).format("YYYY-MM-DD HH:mm:ss")}
        </Typography.Text>
      ),
    },
    {
      title: t("audit.col.eventType"),
      dataIndex: "event_type",
      key: "event_type",
      width: 200,
      render: (v: string) => <Tag color={EVENT_COLOR[v] ?? "default"}>{v}</Tag>,
    },
    {
      title: t("audit.col.aggregate"),
      key: "aggregate",
      width: 280,
      render: (_, row) =>
        row.aggregate_id ? (
          <Space direction="vertical" size={0}>
            {row.aggregate_type && <Tag>{row.aggregate_type}</Tag>}
            <Typography.Text copyable={{ text: row.aggregate_id }} style={{ fontSize: 11 }}>
              <code>{row.aggregate_id}</code>
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: t("audit.col.actor"),
      dataIndex: "actor",
      key: "actor",
      width: 280,
      render: (v: string | null | undefined) =>
        v ? (
          <Typography.Text copyable={{ text: v }} style={{ fontSize: 11 }}>
            <code>{v}</code>
          </Typography.Text>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: t("audit.col.eventId"),
      dataIndex: "event_id",
      key: "event_id",
      width: 110,
      render: (v: string) => (
        <Typography.Text copyable={{ text: v }} style={{ fontSize: 11 }}>
          <code>{v.slice(0, 8)}…</code>
        </Typography.Text>
      ),
    },
  ];

  return (
    <Card title={t("audit.title")}>
      <Card type="inner" size="small" style={{ marginBottom: 16 }} title={t("audit.filters")}>
        <Form form={form} layout="vertical">
          <Row gutter={12}>
            <Col xs={24} md={8}>
              <Form.Item label={t("audit.fld.eventType")} name="eventType">
                <Select
                  allowClear
                  placeholder={t("audit.fld.eventTypeHint")}
                  options={KNOWN_EVENT_TYPES.map((e) => ({ value: e, label: e }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label={t("audit.fld.aggregateType")} name="aggregateType">
                <Select
                  allowClear
                  placeholder={t("audit.fld.aggregateTypeHint")}
                  options={KNOWN_AGGREGATE_TYPES.map((a) => ({ value: a, label: a }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label={t("audit.fld.aggregateId")} name="aggregateId">
                <Input placeholder="UUID" allowClear />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item label={t("audit.fld.actor")} name="actor">
                <Input placeholder="UUID (om_user_id)" allowClear />
              </Form.Item>
            </Col>
            <Col xs={24} md={10}>
              <Form.Item label={t("audit.fld.range")} name="range">
                <DatePicker.RangePicker showTime style={{ width: "100%" }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item label={t("audit.fld.q")} name="q" extra={t("audit.fld.qHint")}>
                <Input placeholder={t("audit.fld.qPlaceholder")} allowClear />
              </Form.Item>
            </Col>
          </Row>
          <Space>
            <Button type="primary" icon={<SearchOutlined />} onClick={applyFilters}>
              {t("audit.apply")}
            </Button>
            <Button icon={<ClearOutlined />} onClick={clearFilters}>
              {t("audit.clear")}
            </Button>
          </Space>
        </Form>
      </Card>

      {q.error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          message={
            q.error instanceof ApiError ? `${q.error.status}: ${q.error.message}` : String(q.error)
          }
        />
      )}

      <Table<AuditEntry>
        rowKey={(r) => `${r.id}-${r.event_type}`}
        loading={q.isLoading}
        dataSource={q.data?.items ?? []}
        columns={columns}
        size="small"
        scroll={{ x: "max-content" }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: q.data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [25, 50, 100, 250, 500],
          showTotal: (total) => t("audit.totalRows", { total }),
          onChange: (next, nextSize) => {
            setPage(next - 1);
            setSize(nextSize);
          },
        }}
        expandable={{
          expandedRowRender: (row) => <PayloadView payload={row.payload} />,
          rowExpandable: (row) => !!row.payload && Object.keys(row.payload).length > 0,
        }}
      />
    </Card>
  );
}

function PayloadView({ payload }: { payload: Record<string, unknown> }) {
  const { t } = useTranslation();
  return (
    <div>
      <Typography.Text strong>{t("audit.payload")}</Typography.Text>
      <pre
        style={{
          marginTop: 4,
          background: "#fafafa",
          padding: 12,
          borderRadius: 4,
          fontSize: 12,
          maxHeight: 360,
          overflow: "auto",
        }}
      >
        {JSON.stringify(payload, null, 2)}
      </pre>
    </div>
  );
}

function trim(s: string | null | undefined): string | null {
  if (!s) return null;
  const t = s.trim();
  return t || null;
}
