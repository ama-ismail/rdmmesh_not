import { useState } from "react";
import { Select, Spin } from "antd";
import { useQuery } from "@tanstack/react-query";

import { adminApi } from "@/api/endpoints";
import { qk } from "@/api/queryClient";

interface UserPickerProps {
  value?: string | null;
  onChange?: (omUserId: string | null) => void;
  placeholder?: string;
  disabled?: boolean;
}

/**
 * Autocomplete-выбор пользователя из identity.rdm_user_mapping (E18.4).
 * Поиск идёт по username / display_name / email через GET /admin/users/search.
 * Видны только пользователи, которые хоть раз логинились в RDM (E2).
 */
export function UserPicker({ value, onChange, placeholder, disabled }: UserPickerProps) {
  const [q, setQ] = useState("");

  const search = useQuery({
    queryKey: qk.admin.userSearch(q),
    queryFn: () => adminApi.searchUsers(q),
    enabled: q.length >= 1,
    staleTime: 10_000,
  });

  return (
    <Select
      showSearch
      allowClear
      filterOption={false}
      value={value ?? undefined}
      placeholder={placeholder ?? "Search user…"}
      disabled={disabled}
      style={{ width: "100%" }}
      notFoundContent={search.isFetching ? <Spin size="small" /> : null}
      onSearch={(text) => setQ(text)}
      onChange={(v) => onChange?.(v ?? null)}
      options={(search.data ?? []).map((u) => ({
        value: u.om_user_id,
        label: `${u.display_name ?? u.username} (${u.username})${u.email ? " · " + u.email : ""}`,
      }))}
    />
  );
}
