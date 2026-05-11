import { useState } from "react";
import { Alert, Button, Form, Input, InputNumber, Modal, Space, App as AntApp } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiMutations } from "@/api/endpoints";
import { qk } from "@/api/queryClient";
import { ApiError } from "@/api/client";

interface Props {
  versionId: string;
  codesetId: string;
}

interface FormValues {
  keyPartsRaw: string;
  labelRu?: string;
  labelEn?: string;
  attributesRaw?: string;
  orderIndex?: number;
}

// Парсит key_parts из строки: "S1" → ["S1"], "[\"S1\",\"BB\"]" → ["S1","BB"].
// Composite keys требуют JSON-array. Single keys можно вводить голой строкой.
function parseKeyParts(raw: string): unknown[] {
  const trimmed = raw.trim();
  if (!trimmed) throw new Error("empty");
  if (trimmed.startsWith("[")) {
    const parsed: unknown = JSON.parse(trimmed);
    if (!Array.isArray(parsed)) throw new Error("must be array");
    return parsed;
  }
  return [trimmed];
}

function parseAttributes(raw: string | undefined): Record<string, unknown> | undefined {
  if (!raw || !raw.trim()) return undefined;
  const parsed: unknown = JSON.parse(raw);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error("attributes must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

export function AddItemButton({ versionId, codesetId }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<FormValues>();
  const [parseError, setParseError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  const create = useMutation({
    mutationFn: (values: FormValues) => {
      const keyParts = parseKeyParts(values.keyPartsRaw);
      const attributes = parseAttributes(values.attributesRaw);
      return apiMutations.createItem(versionId, {
        key_parts: keyParts,
        label_ru: values.labelRu?.trim() || null,
        label_en: values.labelEn?.trim() || null,
        attributes: attributes ?? null,
        order_index: values.orderIndex ?? null,
      });
    },
    onSuccess: () => {
      message.success(t("items.addSuccess"));
      // Items page paginated — invalidate всё дерево items для версии.
      queryClient.invalidateQueries({ queryKey: ["versions", versionId, "items"] });
      queryClient.invalidateQueries({ queryKey: qk.versions.one(versionId) });
      queryClient.invalidateQueries({ queryKey: qk.versions.byCodeset(codesetId) });
      setOpen(false);
      form.resetFields();
      setParseError(null);
    },
    onError: (e: unknown) => {
      // Парсинг JSON падает синхронно в mutationFn — мы его ловим тут.
      if (e instanceof SyntaxError || (e instanceof Error && !(e instanceof ApiError))) {
        setParseError(`${t("items.parseError")}: ${e.message}`);
        return;
      }
      const msg = e instanceof ApiError ? `${e.status}: ${e.message}` : String(e);
      message.error(msg);
    },
  });

  return (
    <>
      <Button icon={<PlusOutlined />} type="primary" onClick={() => setOpen(true)}>
        {t("items.addButton")}
      </Button>
      <Modal
        open={open}
        title={t("items.addTitle")}
        okText={t("items.addOk")}
        cancelText={t("workflow.modal.cancel")}
        confirmLoading={create.isPending}
        onOk={async () => {
          try {
            const values = await form.validateFields();
            setParseError(null);
            create.mutate(values);
          } catch {
            // antd valid'ция, сообщения уже в форме
          }
        }}
        onCancel={() => {
          setOpen(false);
          form.resetFields();
          setParseError(null);
        }}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label={t("items.keyPartsLabel")}
            name="keyPartsRaw"
            rules={[{ required: true, message: t("items.keyPartsRequired") }]}
            extra={t("items.keyPartsHint")}
          >
            <Input placeholder='S1 / ["RETAIL","BB","12M"]' />
          </Form.Item>
          <Space.Compact style={{ width: "100%" }}>
            <Form.Item label={t("items.labelRu")} name="labelRu" style={{ flex: 1 }}>
              <Input maxLength={500} />
            </Form.Item>
            <Form.Item label={t("items.labelEn")} name="labelEn" style={{ flex: 1, marginLeft: 8 }}>
              <Input maxLength={500} />
            </Form.Item>
          </Space.Compact>
          <Form.Item
            label={t("items.attributesLabel")}
            name="attributesRaw"
            extra={t("items.attributesHint")}
          >
            <Input.TextArea rows={4} placeholder='{"stage":"1"}' />
          </Form.Item>
          <Form.Item label={t("items.orderIndex")} name="orderIndex">
            <InputNumber min={0} style={{ width: "100%" }} />
          </Form.Item>
          {parseError && <Alert type="error" message={parseError} showIcon />}
        </Form>
      </Modal>
    </>
  );
}
