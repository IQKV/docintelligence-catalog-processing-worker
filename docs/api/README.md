## 📜 API Documentation

Base path: `/api/v1/catalog`

All endpoints require a valid RS256 JWT issued by the IAM service unless marked as public.
The JWT must be passed as a `Bearer` token in the `Authorization` header.

---

### Ping (Reachability Probes)

| Method | Path           | Auth                 | Description                                                           |
| ------ | -------------- | -------------------- | --------------------------------------------------------------------- |
| `GET`  | `/ping`        | public               | Unauthenticated reachability probe — no JWT or tenant header required |
| `GET`  | `/tenant/ping` | JWT + `X-Tenant-ID`  | Tenant-scoped probe — echoes resolved tenant key                      |
| `GET`  | `/admin/ping`  | JWT `PLATFORM_ADMIN` | Admin probe — echoes caller's `user_id` claim                         |

`GET /ping` is suitable for infrastructure health checks and smoke tests that run before auth is available.

`GET /tenant/ping` verifies that tenant resolution (from `X-Tenant-ID` header or JWT `tenant_id` claim) is working correctly.

`GET /admin/ping` confirms the caller's JWT is decoded correctly and carries the `PLATFORM_ADMIN` authority.

---

### Error Responses

All errors follow RFC 7807 `ProblemDetail` format:

```json
{
    "type": "about:blank",
    "title": "Validation Failed",
    "status": 400,
    "detail": "Request validation failed",
    "instance": "/api/v1/catalog/ping",
    "correlationId": "abc12345",
    "requestId": "req-a1b2c3d4"
}
```

| Status | When                                                   |
| ------ | ------------------------------------------------------ |
| 400    | Validation failure                                     |
| 401    | Missing or invalid JWT                                 |
| 403    | Insufficient authority (e.g. accessing admin endpoints) |
| 500    | Unexpected internal error                              |

---

### Interactive Documentation

Swagger UI is available at `http://localhost:8080/swagger-ui.html` when running locally.
The Gateway aggregates the Catalog Service spec at `/api/v1/catalog/api-docs`.

> Auth legend: `public` = no token required; `JWT` = valid Bearer token; `JWT PLATFORM_ADMIN` = JWT with platform administrator authority.
