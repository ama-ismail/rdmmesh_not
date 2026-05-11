import { useMemo, useState } from "react";
import {
  Button,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Space,
  Table,
  Tooltip,
  App as AntApp,
  type TableColumnsType,
} from "antd";
import { CheckOutlined, CloseOutlined, DeleteOutlined, EditOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { CodeItem } from "@/api/types";
import { apiMutations, type ItemPatchBody } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  items: CodeItem[];
  // E11.2b: inline edit включается только для DRAFT-версий.
  editable?: boolean;
  versionId?: string;
  codesetId?: string;
}

interface EditFormValues {
  labelRu?: string | null;
  labelEn?: string | null;
  orderIndex?: number | null;
  attributesRaw?: string | null;
}

function parseAttributes(raw: string | undefined | null): Record<string, unknown> | null {
  if (!raw || !raw.trim()) return null;
  const parsed: unknown = JSON.parse(raw);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new SyntaxError("attributes must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

export function ItemsTable({ items, editable = false, versionId, codesetId }: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm<EditFormValues>();

  // Сбросить cache invalidation после mutation'ов; reused и patch'ем, и delete'ом.
  const invalidate = () => {
    if (!versionId) return;
    queryClient.invalidateQueries({ queryKey: qk.versions.itemsRoot(versionId) });
    queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
    if (codesetId) {
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
    }
  };

  const patch = useMutation({
    mutationFn: ({ itemId, body }: { itemId: string; body: ItemPatchBody }) =>
      apiMutations.patchItem(versionId!, itemId, body),
    onSuccess: () => {
      message.success(t("items.editSuccess"));
      setEditingId(null);
      form.resetFields();
      invalidate();
    },
    onError: (e: unknown) => {
      // 409 = optimistic lock OR DRAFT-only check; 422 = schema validation
      if (e instanceof ApiError && e.status === 409) {
        message.error(t("items.optimisticConflict"));
        // подтянем свежие данные — пользователь увидит актуальный row_version
        invalidate();
        setEditingId(null);
        form.resetFields();
        return;
      }
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const remove = useMutation({
    mutationFn: (itemId: string) => apiMutations.deleteItem(versionId!, itemId),
    onSuccess: () => {
      message.success(t("items.deleteSuccess"));
      invalidate();
    },
    onError: (e: unknown) => {
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  const startEdit = (it: CodeItem) => {
    form.setFieldsValue({
      labelRu: it.label_ru ?? "",
      labelEn: it.label_en ?? "",
      orderIndex: it.order_index ?? null,
      attributesRaw: it.attributes ? JSON.stringify(it.attributes, null, 2) : "",
    });
    setEditingId(it.id);
  };

  const cancelEdit = () => {
    setEditingId(null);
    form.resetFields();
  };

  const saveEdit = async (it: CodeItem) => {
    let values: EditFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    let attributes: Record<string, unknown> | null;
    try {
      attributes = parseAttributes(values.attributesRaw ?? "");
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      message.error(`${t("items.parseError")}: ${msg}`);
      return;
    }
    if (it.row_version == null) {
      message.error("row_version отсутствует — обновите страницу");
      return;
    }
    patch.mutate({
      itemId: it.id,
      body: {
        expected_row_version: it.row_version,
        label_ru: values.labelRu?.trim() || null,
        label_en: values.labelEn?.trim() || null,
        order_index: values.orderIndex ?? null,
        attributes,
      },
    });
  };

  const attrKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const it of items) {
      if (it.attributes) {
        for (const k of Object.keys(it.attributes)) keys.add(k);
      }
    }
    return Array.from(keys).sort();
  }, [items]);

  const columns: TableColumnsType<CodeItem> = useMemo(() => {
    const isEditing = (id: string) => editingId === id;
    const renderEdit = (input: React.ReactNode, id: string, fallback: React.ReactNode) =>
      isEditing(id) ? input : fallback;

    const base: TableColumnsType<CodeItem> = [
      {
        title: t("items.key"),
        key: "key",
        fixed: "left",
        render: (_, row) => (
          <code>
            {Array.isArray(row.key_parts) ? row.key_parts.join(" / ") : String(row.key_parts)}
          </code>
        ),
        width: 200,
      },
      {
        title: t("items.labelRu"),
        key: "label_ru",
        width: 180,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="labelRu" style={{ margin: 0 }}>
              <Input size="small" />
            </Form.Item>,
            row.id,
            row.label_ru ?? "",
          ),
      },
      {
        title: t("items.labelEn"),
        key: "label_en",
        width: 180,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="labelEn" style={{ margin: 0 }}>
              <Input size="small" />
            </Form.Item>,
            row.id,
            row.label_en ?? "",
          ),
      },
    ];

    // Атрибуты: в режиме редактирования прячем разнесённые колонки и показываем
    // одну textarea-колонку attributesRaw (JSON). В обычном режиме — колонки
    // на каждый ключ (как round 1).
    const attrs: TableColumnsType<CodeItem> = editingId
      ? [
          {
            title: t("items.attributes"),
            key: "attributes_edit",
            width: 280,
            render: (_, row) =>
              renderEdit(
                <Form.Item
                  name="attributesRaw"
                  style={{ margin: 0 }}
                  extra={t("items.attributesHint")}
                >
                  <Input.TextArea rows={3} size="small" placeholder='{"stage":"1"}' />
                </Form.Item>,
                row.id,
                row.attributes ? <code>{JSON.stringify(row.attributes)}</code> : "",
              ),
          },
        ]
      : attrKeys.map((k) => ({
          title: k,
          key: `attr.${k}`,
          render: (_, row) => formatAttr(row.attributes?.[k]),
          width: 140,
        }));

    const tail: TableColumnsType<CodeItem> = [
      {
        title: t("items.orderIndex"),
        key: "order_index",
        width: 110,
        render: (_, row) =>
          renderEdit(
            <Form.Item name="orderIndex" style={{ margin: 0 }}>
              <InputNumber size="small" min={0} style={{ width: "100%" }} />
            </Form.Item>,
            row.id,
            row.order_index ?? "",
          ),
      },
      { title: t("items.status"), dataIndex: "status", key: "status", width: 100 },
      { title: t("items.rowVersion"), dataIndex: "row_version", key: "row_version", width: 110 },
    ];

    if (!editable) {
      return [...base, ...attrs, ...tail];
    }

    const actions: TableColumnsType<CodeItem> = [
      {
        title: t("items.actions"),
        key: "actions",
        fixed: "right",
        width: 140,
        render: (_, row) =>
          isEditing(row.id) ? (
            <Space size={4}>
              <Tooltip title={t("items.save")}>
                <Button
                  type="primary"
                  size="small"
                  icon={<CheckOutlined />}
                  loading={patch.isPending}
                  onClick={() => void saveEdit(row)}
                />
              </Tooltip>
              <Tooltip title={t("items.cancel")}>
                <Button size="small" icon={<CloseOutlined />} onClick={cancelEdit} />
              </Tooltip>
            </Space>
          ) : (
            <Space size={4}>
              <Tooltip title={t("items.edit")}>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  disabled={editingId !== null || remove.isPending}
                  onClick={() => startEdit(row)}
                />
              </Tooltip>
              <Popconfirm
                title={t("items.deleteConfirmTitle")}
                description={t("items.deleteConfirmDescription")}
                okText={t("version.deleteConfirmOk")}
                okButtonProps={{ danger: true }}
                cancelText={t("workflow.modal.cancel")}
                onConfirm={() => remove.mutate(row.id)}
              >
                <Tooltip title={t("items.deleteRow")}>
                  <Button
                    danger
                    size="small"
                    icon={<DeleteOutlined />}
                    disabled={editingId !== null}
                  />
                </Tooltip>
              </Popconfirm>
            </Space>
          ),
      },
    ];

    return [...base, ...attrs, ...tail, ...actions];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t, attrKeys, editingId, editable, patch.isPending, remove.isPending]);

  const table = (
    <Table<CodeItem>
      rowKey={(r) => r.id}
      dataSource={items}
      columns={columns}
      size="small"
      pagination={false}
      scroll={{ x: "max-content", y: 480 }}
      sticky
    />
  );

  if (!editable) return table;
  return <Form form={form}>{table}</Form>;
}

function formatAttr(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") {
    return String(v);
  }
  return JSON.stringify(v);
}
