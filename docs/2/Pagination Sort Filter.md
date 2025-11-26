# Backend & Frontend Documentation: Pagination, Sorting, Filtering

---

## **1. Overview**

This document describes the complete architecture and workflow for:

* Pagination
* Sorting
* Filtering
* Shared backend utilities
* Frontend URL-based state management
* How queries are appended to URLs
* How queries are read from URLs
* How requests are chained and sent to the backend

Everything is standardized using a reusable backend `QueryUtil` and a predictable frontend query system.

---

# **2. Backend Architecture**

## **2.1 Goals**

* Avoid repeated logic in handlers
* Allow consistent pagination, sorting, and filtering across all endpoints
* Make filters and sort keys controlled and safe (map-based)
* Automatically generate: `WHERE`, `ORDER BY`, `LIMIT`, `OFFSET`
* Produce pagination response using `sendPaginated()`

---

# **3. Backend Utility: QueryUtil**

This utility builds all SQL components for any handler.

## **3.1 Input Parameters**

`QueryUtil.build()` accepts:

* The `HttpServerExchange`
* A `FILTER_MAP` (URL → DB column)
* A `SORT_MAP` (URL → DB column)
* The default sort column

## **3.2 Outputs (QueryParts Record)**

`QueryParts` contains:

* `where` — fully built WHERE clause
* `orderBy` — ORDER BY clause
* `params` — list of prepared-statement parameters
* `page` — current page
* `pageSize` — size per page
* `offset` — calculated offset

---

# **4. Backend Filtering Logic**

Each filter is controlled by a `FILTER_MAP`:

```java
private static final Map<String, String> FILTER_MAP = Map.of(
  "service", "monitored_service_id",
  "status", "status",
  "region", "region",
  "code", "http_status"
);
```

If the URL contains:

```
?status=DOWN&region=eu
```

`QueryUtil` builds:

```
WHERE status = ? AND region = ?
```

Parameters become:

```
["DOWN", "eu"]
```

---

# **5. Backend Sorting Logic**

Sorting is controlled by a `SORT_MAP`:

```java
private static final Map<String, String> SORT_MAP = Map.of(
  "checked", "checked_at",
  "service", "monitored_service_id",
  "status", "status"
);
```

If the URL contains:

```
?sort=checked:desc,status:asc
```

`QueryUtil` generates:

```
ORDER BY checked_at DESC, status ASC
```

If no sort is provided → uses default:

```
ORDER BY checked_at DESC
```

---

# **6. Backend Pagination Logic**

Query parameters:

* `page=1`
* `pageSize=20`

Offset rule:

```
offset = (page - 1) * pageSize
```

SQL output:

```
LIMIT ? OFFSET ?
```

Both are appended **after** filters and sorting.

---

# **7. Sending the Response (sendPaginated)**

All paginated endpoints return the same JSON structure:

```java
sendPaginated(exchange, "uptime_logs", page, pageSize, totalCount, data);
```

Response structure:

```
{
  "domain": "uptime_logs",
  "current_page": 1,
  "last_page": 5,
  "page_size": 20,
  "total_count": 83,
  "data": [ ... ]
}
```

---

# **8. Example Backend Code Using QueryUtil**

The handler becomes extremely small:

```java
QueryUtil.QueryParts qp = QueryUtil.build(exchange, FILTER_MAP, SORT_MAP, "checked_at");
```

Now handlers only do:

* build SQL
* execute query
* return paginated response

---

# **9. Frontend Architecture**

The frontend uses **URL-based state** for pagination, sorting, and filtering.

This ensures:

* refresh persistence
* deep linking
* shareable lists
* back/forward browser navigation

Example URL:

```
/services/logs/uptime?page=2&pageSize=20&status=DOWN&region=default&sort=checked:desc
```

---

# **10. Frontend: Writing to URL**

Whenever the user:

* changes page
* changes pageSize
* applies a filter
* applies sorting

The frontend updates the URL:

### Example React/TanStack Router

```ts
router.navigate({
  to: "/services/logs/uptime",
  search: {
    page,
    pageSize,
    status,
    region,
    sort
  }
});
```

---

# **11. Frontend: Reading From URL**

```ts
const { page, pageSize, status, region, sort } = useSearch();
```

These values are immediately usable for API calls.

---

# **12. Frontend: Chaining Request to Backend**

```ts
const query = new URLSearchParams({
  page,
  pageSize,
  status,
  region,
  sort
});

api.get(`/services/logs/uptime?${query.toString()}`);
```

This produces clean URLs automatically.

---

# **13. Example Final Full URL**

```
/services/logs/uptime?page=3&pageSize=50&status=UP&region=east-africa&sort=checked:desc,response:asc
```

---

# **14. Summary**

### **Backend**

* Universal utility `QueryUtil` builds all SQL logic
* Filters controlled by `FILTER_MAP`
* Sorting controlled by `SORT_MAP`
* Pagination always consistent
* `sendPaginated` produces a unified JSON response

### **Frontend**

* URL is the single source of truth
* State is encoded inside URL Query Params
* Deep linking and navigation work reliably
* Clean request chaining using URLSearchParams

---

This documentation ensures future developers can easily extend or maintain any paginated, sortable, or filterable endpoint.
