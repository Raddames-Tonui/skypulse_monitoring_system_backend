✅ THE 3 COMPONENTS YOU PICKED
1️⃣ Scheduled Task Framework (Recommended ✔)

FlexiCore uses:

ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
executor.scheduleAtFixedRate(() -> { ... }, 0, 1, TimeUnit.HOURS);


Use this for:

Uptime checking

SSL expiry checking

Log cleanup

Alert retries

Email notifications

System health collection

Lightweight. Fast. Zero dependencies.

2️⃣ Cryptography + Integrity Hashing (Essential ✔)

FlexiCore uses:

BouncyCastle Provider

Hashing for record integrity

AES encryption

SHA-256 + salts

Integrity hash to detect tampering

Why you want this:

You want to encrypt:

Private fields

Sensitive logs

Sensitive configurations

Service tokens

API credentials

OAuth/BC tokens

And also create tamper-proof audit trails.

❓ Now the missing question:
What do they use for sessions? JWT?
✔ FlexiCore DOES NOT use JWT.

It uses server-side session objects, not token-based auth.

Their stack is older and banking-oriented, so they use:

🔹 Session variables
🔹 Mutual SSL client certificates
🔹 Server-side “integrity hash” for each user
🔹 Encrypted session objects stored in DB
🔹 Custom token-like objects

This is NOT modern and not needed for your systems.

✅ WHAT YOU SHOULD USE INSTEAD

For SkyPulse and SACCO System, JWT is 100% the right choice.

✔ JWT for Authentication

You will implement:

Access Token (JWT)

short-lived (10–30 minutes)

signed using HS256 or RS256

stored in memory in the frontend

sent via Authorization: Bearer <token>

Refresh Token

long-lived (1–30 days)

stored in secure HttpOnly cookie

stored in auth_sessions table:

auth_sessions (
auth_session_id UUID,
user_id UUID,
jwt_id UUID,
expires_at TIMESTAMPTZ
)

✔ Refresh token rotates

If refresh token is stolen → old one becomes invalid automatically.

✔ JWT fits PERFECTLY with your new SACCO schema

You already created:

auth_sessions
login_attempts
audit_log
users
roles
role_permissions
permissions


This is textbook token-based RBAC authentication.

🚀 ARCHITECTURE YOU SHOULD USE (Final Recommendation)
🔐 A) Authentication Stack

JWT (modern, stateless)

Access token (15m)

Refresh token (7 days)

Use Java library:

jjwt

or auth0-java-jwt

Flow:

POST /auth/login

Validate password with BCrypt

Generate JWT

Save refresh token in DB

Set refresh token cookie

Client sends access JWT with each request

🛡 B) Cryptography + Integrity Hashing

Use for:

sensitive DB fields (emails, IPs, tokens)

audit logs

PDF access logs

event queue items

system secret keys

random generators

hashing for request integrity

You SHOULD use BouncyCastle.

🔁 C) Scheduled Task Framework

Use scheduled executor for:

uptime check loop

SSL expiry monitor

event queue processor

log retention cleanup

disk health check

background retries

notification dispatch

🧩 How they fit together in your systems
SACCO BACKEND

✔ JWT login
✔ BCrypt passwords
✔ Crypto for sensitive fields
✔ Scheduled tasks for:

auto-deactivating accounts

scheduled notifications

nightly DB cleanup

SkyPulse Monitoring

✔ Scheduled pingers
✔ Scheduled SSL expiry scan
✔ Crypto for:

stored service secrets

API credentials

webhook tokens
✔ JWT login to dashboard
✔ Role-permission gating in frontend

🧠 So your final architecture becomes a beast:

JWT for secure sessions

BCrypt for password hashing

AES-GCM for encrypted fields

SHA-256 integrity hash for tamper detection

Scheduled tasks for monitoring

Undertow as lightweight server

HikariCP for Postgres

React frontend for dashboards

Role-based permissions on pages

Audit logging everywhere

This is enterprise-grade, clean, fast, modern.

Want me to add JWT Authentication to your ZIP project?

I can generate:

/auth/login

/auth/refresh

/auth/logout

Access + refresh tokens

Secure HttpOnly cookie

Middleware that checks JWT on every request

React frontend login page

Role-based protected routes

Permissions-based rendering in React