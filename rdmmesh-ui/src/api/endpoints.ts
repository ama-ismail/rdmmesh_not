import { apiFetch } from "./client";
import type {
  ApprovalTask,
  AuditPage,
  AuthMe,
  BulkImportResult,
  CodeItem,
  CodeSet,
  CodeSetSchemaDto,
  CodeSetVersion,
  Domain,
  ItemsPage,
  Subscription,
  SubscriptionFilter,
  VerifyResponse,
  VersionDiffResponse,
  VersionStatus,
  WorkflowTransitionEvent,
} from "./types";

// ─── Read-side ────────────────────────────────────────────────────────────────

export const api = {
  authMe: () => apiFetch<AuthMe>("/auth/me"),

  // Catalog
  listDomains: () => apiFetch<Domain[]>("/domains"),
  getDomain: (id: string) => apiFetch<Domain>(`/domains/${id}`),
  listCodeSetsByDomain: (domainId: string) =>
    apiFetch<CodeSet[]>(`/codesets/by-domain/${domainId}`),
  getCodeSet: (id: string) => apiFetch<CodeSet>(`/codesets/${id}`),
  getActiveSchema: (codesetId: string) =>
    apiFetch<CodeSetSchemaDto>(`/codesets/${codesetId}/schema`),
  getSchemaHistory: (codesetId: string) =>
    apiFetch<CodeSetSchemaDto[]>(`/codesets/${codesetId}/schema/history`),

  // Authoring (read)
  listVersionsByCodeSet: (codesetId: string) =>
    apiFetch<CodeSetVersion[]>(`/versions/by-codeset/${codesetId}`),
  getVersion: (id: string) => apiFetch<CodeSetVersion>(`/versions/${id}`),
  listItems: (versionId: string, page = 0, size = 100) =>
    apiFetch<ItemsPage>(`/versions/${versionId}/items?page=${page}&size=${size}`),
  diffVersions: (toVersionId: string, fromVersionId: string) =>
    apiFetch<VersionDiffResponse>(
      `/versions/${toVersionId}/diff?from=${encodeURIComponent(fromVersionId)}`,
    ),

  // Workflow
  transitionHistory: (versionId: string) =>
    apiFetch<WorkflowTransitionEvent[]>(`/versions/${versionId}/history`),
  myTasks: () => apiFetch<ApprovalTask[]>("/tasks/my"),

  // Publishing
  verifyVersion: (versionId: string) =>
    apiFetch<VerifyResponse>(`/versions/${versionId}/verify`),

  // Subscriptions (E9, RDM_ADMIN)
  listSubscriptions: () => apiFetch<Subscription[]>("/subscriptions"),
  getSubscription: (id: string) => apiFetch<Subscription>(`/subscriptions/${id}`),

  // Audit viewer (E11.2d, RDM_ADMIN). Все фильтры опциональны.
  listAuditEntries: (
    page: number,
    size: number,
    filters: AuditQuery = {},
  ) => {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("size", String(size));
    if (filters.eventType) params.set("event_type", filters.eventType);
    if (filters.aggregateType) params.set("aggregate_type", filters.aggregateType);
    if (filters.aggregateId) params.set("aggregate_id", filters.aggregateId);
    if (filters.actor) params.set("actor", filters.actor);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.q) params.set("q", filters.q);
    return apiFetch<AuditPage>(`/audit?${params.toString()}`);
  },
};

export interface AuditQuery {
  eventType?: string | null;
  aggregateType?: string | null;
  aggregateId?: string | null;
  actor?: string | null;
  from?: string | null;
  to?: string | null;
  q?: string | null;
}

// ─── Mutations ────────────────────────────────────────────────────────────────

// POST /versions/by-codeset/{codesetId}
export interface CreateDraftRequest {
  version?: string;
  initial_items?: CodeItem[];
}

export const apiMutations = {
  // E11.2a — DRAFT lifecycle + workflow transitions
  createDraft: (codesetId: string, body: CreateDraftRequest = {}) =>
    apiFetch<CodeSetVersion>(`/versions/by-codeset/${codesetId}`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  deleteDraft: (versionId: string) =>
    apiFetch<void>(`/versions/${versionId}`, { method: "DELETE" }),

  createItem: (versionId: string, item: NewItemBody) =>
    apiFetch<CodeItem>(`/versions/${versionId}/items`, {
      method: "POST",
      body: JSON.stringify(item),
    }),

  transition: (versionId: string, body: TransitionRequest) =>
    apiFetch<WorkflowTransitionEvent>(`/versions/${versionId}/transitions`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // E11.2b — items editor
  patchItem: (versionId: string, itemId: string, patch: ItemPatchBody) =>
    apiFetch<CodeItem>(`/versions/${versionId}/items/${itemId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),

  deleteItem: (versionId: string, itemId: string) =>
    apiFetch<void>(`/versions/${versionId}/items/${itemId}`, { method: "DELETE" }),

  // E11.2b — bulk import
  bulkJson: (versionId: string, rows: NewItemBody[]) =>
    apiFetch<BulkImportResult>(`/versions/${versionId}/items/bulk`, {
      method: "POST",
      body: JSON.stringify(rows),
    }),

  // CSV — голый text/csv body, без оборачивания в JSON.
  bulkCsv: (versionId: string, csv: string) =>
    apiFetch<BulkImportResult>(`/versions/${versionId}/items/bulk-csv`, {
      method: "POST",
      headers: { "Content-Type": "text/csv" },
      body: csv,
    }),

  // E11.2c — Subscriptions admin
  createSubscription: (body: SubscriptionCreateRequest) =>
    apiFetch<Subscription>("/subscriptions", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // soft-delete (active=false) — backend никогда не удаляет физически
  deleteSubscription: (id: string) =>
    apiFetch<void>(`/subscriptions/${id}`, { method: "DELETE" }),
};

export interface SubscriptionCreateRequest {
  url: string;
  secret_id: string;
  filter?: SubscriptionFilter | null;
  active?: boolean | null;
}

export interface NewItemBody {
  key_parts: unknown[];
  parent_key?: unknown[] | null;
  label_ru?: string | null;
  label_en?: string | null;
  description_ru?: string | null;
  description_en?: string | null;
  attributes?: Record<string, unknown> | null;
  order_index?: number | null;
  status?: string | null;
  effective_from?: string | null;
  effective_to?: string | null;
}

// expected_row_version обязателен на backend'е (CodeItemResource.java:306) — иначе 400.
export interface ItemPatchBody {
  expected_row_version: number;
  parent_key?: unknown[] | null;
  parent_ref?: Record<string, unknown> | null;
  label_ru?: string | null;
  label_en?: string | null;
  description_ru?: string | null;
  description_en?: string | null;
  attributes?: Record<string, unknown> | null;
  order_index?: number | null;
  status?: string | null;
  effective_from?: string | null;
  effective_to?: string | null;
}

export interface TransitionRequest {
  to: VersionStatus;
  comment?: string | null;
  expected_status?: VersionStatus | null;
}
