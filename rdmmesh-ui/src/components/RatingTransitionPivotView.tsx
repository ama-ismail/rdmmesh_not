import { useMemo, useState } from "react";
import { Empty, Select, Space, Table, Tag, Tooltip, Typography, type TableColumnsType } from "antd";
import { useTranslation } from "react-i18next";

import type { CodeItem } from "@/api/types";

/**
 * RatingTransitionPivotView — pivot-вью матрицы миграций рейтингов (E19 Commit 2).
 *
 * Активируется в VersionPage, когда у CodeSet есть тег `kind:transition_matrix`
 * (см. E19 handoff §2.3 — выбор tags вместо отдельной колонки `kind` для Slice C).
 * Ожидаемая форма key_parts: [from_rating, to_rating, horizon].
 *
 * Read-only в Commit 2 — пользователь редактирует через стандартный Long-вью
 * (ItemsTable). Inline-edit ячеек pivot'а — в Commit 3 или V1+.
 *
 * TODO(E19 V1+): порядок рейтингов сейчас захардкожен KNOWN_RATING_ORDER. В
 * проде брать из cross-codeset CodeSet'а `rating_scale` (атрибут `order`, см.
 * seed-credit-risk.sh). Для Slice C достаточно: незнакомые рейтинги добавляются
 * после известных по алфавиту.
 */

// Известный порядок рейтингов в seed (AAA лучший, D — absorbing/дефолт).
// Незнакомые значения → в конец по алфавиту.
const KNOWN_RATING_ORDER = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D"] as const;

// Стандартная банковская шкала DPD (Days Past Due). Используется для
// delinquency-матриц — порядок «по тяжести просрочки», closed/current — терминал.
const KNOWN_DPD_ORDER = [
  "current",
  "0d",
  "0",
  "1-30d",
  "1-30",
  "30d",
  "31-60d",
  "31-90d",
  "60d",
  "90d",
  "90+d",
  "91+d",
  "default",
  "closed",
] as const;

// Если в большинстве строк сумма далека от 1 — это не стохастическая матрица
// (например delinquency flow-rates). В этом случае Σ-колонка не должна красниться:
// сумма ≠ 1 для таких матриц — норма, не ошибка.
function isStochasticMatrix(sums: number[]): boolean {
  if (sums.length === 0) return true;
  const eps = 1e-3;
  const okCount = sums.filter((s) => Math.abs(s - 1) <= eps).length;
  return okCount >= Math.max(1, Math.floor(sums.length / 2));
}

interface Props {
  items: CodeItem[];
}

export function RatingTransitionPivotView({ items }: Props) {
  const { t } = useTranslation();

  // 1. Discover horizons (key_parts[2]) — для Select'а
  const horizons = useMemo(() => {
    const set = new Set<string>();
    for (const it of items) {
      if (it.key_parts && it.key_parts.length >= 3 && it.key_parts[2]) {
        set.add(it.key_parts[2]);
      }
    }
    // Сортируем горизонты по «логической длительности» — fallback на alpha.
    const order = ["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"];
    return Array.from(set).sort((a, b) => {
      const ia = order.indexOf(a);
      const ib = order.indexOf(b);
      if (ia === -1 && ib === -1) return a.localeCompare(b);
      if (ia === -1) return 1;
      if (ib === -1) return -1;
      return ia - ib;
    });
  }, [items]);

  // Default — первый доступный горизонт (обычно 1Y или 1M).
  const defaultHorizon = horizons[0] ?? null;
  const [horizon, setHorizon] = useState<string | null>(defaultHorizon);
  // Если props.items пересоздан после фетча и поменялся набор горизонтов — обновим.
  if (horizon === null && defaultHorizon !== null) {
    setHorizon(defaultHorizon);
  }

  // 2. Фильтруем по выбранному горизонту.
  const filtered = useMemo(
    () => items.filter((it) => (it.key_parts?.[2] ?? null) === horizon),
    [items, horizon],
  );

  // 3. Discover оси (rows = from, cols = to) из отфильтрованных items.
  const { axis, cellMap } = useMemo(() => {
    const fromSet = new Set<string>();
    const toSet = new Set<string>();
    const map = new Map<string, number>(); // key "from|to" → probability
    for (const it of filtered) {
      if (!it.key_parts || it.key_parts.length < 3) continue;
      const from = it.key_parts[0];
      const to = it.key_parts[1];
      if (!from || !to) continue;
      fromSet.add(from);
      toSet.add(to);
      const p = it.attributes?.probability;
      if (typeof p === "number" && isFinite(p)) {
        map.set(`${from}|${to}`, p);
      }
    }
    // Объединяем from и to — оси должны быть одинаковы (квадратная матрица).
    const all = new Set<string>([...fromSet, ...toSet]);
    const axis = sortRatings(Array.from(all));
    return { axis, cellMap: map };
  }, [filtered]);

  if (horizons.length === 0) {
    return (
      <Empty
        description={t("pivot.noData")}
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  // 4. Строки таблицы — по from_rating.
  type Row = { from: string; sum: number; isAbsorbing: boolean };
  const rows: Row[] = axis.map((from) => {
    let sum = 0;
    for (const to of axis) {
      const p = cellMap.get(`${from}|${to}`);
      if (typeof p === "number") sum += p;
    }
    // Эвристика absorbing: D→D=1 и все остальные D→x=0.
    const selfP = cellMap.get(`${from}|${from}`) ?? 0;
    const allOthers = axis
      .filter((to) => to !== from)
      .every((to) => (cellMap.get(`${from}|${to}`) ?? 0) === 0);
    const isAbsorbing = selfP === 1 && allOthers;
    return { from, sum, isAbsorbing };
  });

  // Если матрица не стохастическая (delinquency / flow-rates) — не красним Σ.
  const stochastic = isStochasticMatrix(rows.map((r) => r.sum));

  const columns: TableColumnsType<Row> = [
    {
      title: t("pivot.fromHeader"),
      dataIndex: "from",
      key: "from",
      fixed: "left",
      width: 90,
      render: (from: string, row) => (
        <Space>
          <strong>{from}</strong>
          {row.isAbsorbing && (
            <Tooltip title={t("pivot.absorbingHint")}>
              <Tag color="default">{t("pivot.absorbingShort")}</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    // Колонки = to_rating
    ...axis.map((to) => ({
      title: <code>{to}</code>,
      key: `to_${to}`,
      align: "right" as const,
      width: 86,
      render: (_v: unknown, row: Row) => {
        const p = cellMap.get(`${row.from}|${to}`);
        if (p === undefined) return <Typography.Text type="secondary">—</Typography.Text>;
        return <span style={diagStyle(row.from === to, p)}>{fmt(p)}</span>;
      },
    })),
    {
      title: <Tooltip title={t("pivot.sumHint")}>Σ</Tooltip>,
      dataIndex: "sum",
      key: "sum",
      align: "right",
      width: 90,
      render: (sum: number) => (
        <strong style={{ color: stochastic ? stochasticColor(sum) : "inherit" }}>
          {fmt(sum)}
        </strong>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Typography.Text strong>{t("pivot.horizon")}:</Typography.Text>
        <Select<string>
          value={horizon ?? undefined}
          onChange={(v) => setHorizon(v)}
          style={{ minWidth: 90 }}
          options={horizons.map((h) => ({ label: h, value: h }))}
        />
        <Typography.Text type="secondary" style={{ marginLeft: 12 }}>
          {t("pivot.cellLegend")}
        </Typography.Text>
      </Space>
      <Table<Row>
        rowKey="from"
        size="small"
        bordered
        pagination={false}
        columns={columns}
        dataSource={rows}
        scroll={{ x: "max-content" }}
      />
    </div>
  );
}

// ── helpers ─────────────────────────────────────────────────────────────────

function fmt(p: number): string {
  return p.toFixed(3);
}

function diagStyle(isDiagonal: boolean, p: number): React.CSSProperties {
  const base: React.CSSProperties = {
    fontFamily: "var(--font-mono, monospace)",
  };
  if (isDiagonal) base.fontWeight = 600;
  if (p === 0) base.color = "#bbb";
  return base;
}

// Зелёный — сумма ≈ 1 (стохастична); красный иначе.
function stochasticColor(sum: number): string {
  const eps = 1e-3;
  return Math.abs(sum - 1) <= eps ? "#52c41a" : "#cf1322";
}

function sortRatings(values: string[]): string[] {
  // Сначала пробуем DPD-порядок (delinquency), потом rating-порядок, потом alpha.
  // Префиксное сравнение для DPD устойчиво к регистру и пробелам.
  const norm = (v: string) => v.trim().toLowerCase();
  const dpdIdx = (v: string) => {
    const n = norm(v);
    const idx = (KNOWN_DPD_ORDER as readonly string[]).indexOf(n);
    return idx >= 0 ? idx : -1;
  };
  const ratingIdx = (v: string) => (KNOWN_RATING_ORDER as readonly string[]).indexOf(v);

  const dpdHits = values.filter((v) => dpdIdx(v) >= 0).length;
  const ratingHits = values.filter((v) => ratingIdx(v) >= 0).length;
  const useDpd = dpdHits >= ratingHits && dpdHits > 0;

  const key = useDpd ? dpdIdx : ratingIdx;
  return [...values].sort((a, b) => {
    const ka = key(a);
    const kb = key(b);
    const ea = ka < 0 ? Number.MAX_SAFE_INTEGER : ka;
    const eb = kb < 0 ? Number.MAX_SAFE_INTEGER : kb;
    if (ea !== eb) return ea - eb;
    return a.localeCompare(b);
  });
}
