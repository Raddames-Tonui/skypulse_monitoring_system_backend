
Adopt “Layered Hexagonal Lite” — N-Tier structure with a few Hexagonal principles.

src/main/java/com/skyworld/uptime/
├─ config/              # XML loader, DB setup
├─ api/                 # Undertow handlers
│  ├─ auth/
│  ├─ monitoring/
│  ├─ ssl/
│  └─ notifications/
├─ service/             # Business logic
│  ├─ AuthService.java
│  ├─ MonitoringService.java
│  ├─ SSLService.java
│  └─ NotificationService.java
├─ repository/          # Direct Postgres SQL / QueryBuilder
│  ├─ UserRepository.java
│  ├─ ServiceRepository.java
│  ├─ UptimeRepository.java
│  └─ SSLRepository.java
├─ model/               # Plain domain objects
│  ├─ User.java
│  ├─ Service.java
│  ├─ UptimeLog.java
│  └─ SSLLog.java
├─ util/                # CaptchaUtil, ResponseUtil, etc.
└─ Main.java



                 FRONTEND (React)
                         │
                         ▼
┌──────────────────────────────────────────────┐
│           Inbound Adapter (Undertow)         │
│ AuthHandler implements HttpHandler           │
│   - Parses JSON                              │
│   - Calls port: AuthUseCase.login()          │
└──────────────────────────────────────────────┘
│
▼
┌──────────────────────────────────────────────┐
│           Application Core (Domain)          │
│ AuthUseCase (interface)                      │
│ AuthService implements AuthUseCase           │
│   - Validates input                          │
│   - Calls port: UserRepository.findByEmail() │
│   - Uses BCryptUtil + JwtUtil                │
│   - Emits event: “UserLoggedIn”              │
└──────────────────────────────────────────────┘
│
▼
┌──────────────────────────────────────────────┐
│          Outbound Adapters (Infrastructure)  │
│ UserRepositoryPg implements UserRepository   │
│   - Talks to PostgreSQL via DatabaseManager  │
│ AuditLogger implements AuditLogPort          │
│ JwtAdapter implements TokenPort              │
│ CaptchaStore implements CaptchaPort          │
└──────────────────────────────────────────────┘
│
▼
┌──────────────────────────────────────────────┐
│                 PostgreSQL DB                │
│   users, roles, auth_sessions, audit_log     │
└──────────────────────────────────────────────┘

