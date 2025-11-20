# SkyPulse API  MIDDLEWARE

## Overview

SkyPulse REST API is built using Undertow and provides role-based access to system settings, monitored services, and other core resources. It uses JWT for authentication and includes asynchronous request handling for performance.

## Request Flow

The following handlers manage requests:

```
Client Request
      |
      v
+--------------------+
|   CORSHandler      |  <-- Adds CORS headers
|  (OPTIONS? 204)    |
+--------------------+
      |
      v
+--------------------+
|    Dispatcher      |  <-- Async dispatch to worker thread
+--------------------+
      |
      v
+--------------------+
|  AuthMiddleware    |  <-- Validates JWT, session, attaches UserContext
+--------------------+
      |
      v
+--------------------+
| Business Handler   |  <-- e.g., UpsertSystemSettingHandler
| (Admin check, DB) |
+--------------------+
      |
      v
  Response sent
      |
      +--> If HTTP method invalid -> InvalidMethod
      |
      +--> If unknown URI -> FallBack
```

### Handler Descriptions

1. **CORSHandler**

    * Adds `Access-Control-*` headers.
    * Handles OPTIONS preflight requests immediately (204).

2. **Dispatcher**

    * Dispatches request to a worker thread.
    * Prevents blocking I/O threads for database/network operations.

3. **AuthMiddleware**

    * Validates JWT token and session.
    * Checks user is active and not deleted.
    * Attaches `UserContext` with `userId` and `roleName`.

4. **Business Handlers**

    * Use `UserContext` for role-based access.
    * Perform database operations (e.g., create/update system settings).
    * Respond with JSON via `ResponseUtil`.

5. **InvalidMethod**

    * Returns 405 with JSON when HTTP method is not allowed.

6. **FallBack**

    * Returns 404 with JSON when URI does not exist.

## Authentication & Role-based Access

* JWT tokens contain:

    * `sub` (user UUID)
    * `jti` (JWT ID)
    * `email`
    * `role`

* `UserContext` attached in `AuthMiddleware`:

```java
public class UserContext {
    private final Long userId;
    private final UUID uuid;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final Integer roleId;
    private final String roleName; // fetched from DB
}
```

* Roles:

    * **Admin**: Manage users, services, groups, templates, settings.
    * **Operator**: Add/edit services, manage reports, view dashboards.
    * **Viewer**: Read-only access.

## JSON Utilities

* `JsonUtil` provides a singleton `ObjectMapper`:

```java
ObjectMapper mapper = JsonUtil.mapper();
```

* `ResponseUtil` provides standardized JSON responses:

```java
ResponseUtil.sendError(exchange, 401, "Unauthorized");
ResponseUtil.sendSuccess(exchange, "Operation successful", data);
ResponseUtil.sendCreated(exchange, "Resource created", data);
```

## System Settings Example

* Upsert handler respects defaults and partial updates:

```json
{
  "key": "uptime_check_interval",
  "value": "10",
  "description": "Interval in minutes"
}
```

* Only provided fields overwrite existing values; all others remain untouched.

## CORS

* `CORSHandler` adds headers:

    * `Access-Control-Allow-Origin`
    * `Access-Control-Allow-Credentials`
    * `Access-Control-Allow-Methods`
    * `Access-Control-Allow-Headers`
* OPTIONS requests return 204 without forwarding to business handlers.

## Conclusion

* All endpoints return consistent JSON responses.
* Unauthorized or insufficient role requests return 401/403 JSON errors.
* Invalid methods and unknown URIs return 405/404 JSON errors.
* Dispatcher ensures async handling and high throughput.
