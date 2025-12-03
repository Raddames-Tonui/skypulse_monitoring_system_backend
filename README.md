# SkyPulse Backend

## Overview

SkyPulse backend provides continuous uptime checks, SSL certificate monitoring, notification dispatching, and data APIs backed by a resilient event‑driven architecture.

The backend is built in **Java**, uses **PostgreSQL** as its primary database, and is deployed on **Render**. Security is enforced through signed cookies and structured access controls.

[Live URL]([Backend link ](https://skypulse-monitoring-system-backend.onrender.com/api/rest/system/health))

## Architecture Summary

* **Language:** Java (Undertow)
* **Database:** PostgreSQL
* **Deployment:** Render
* **Security:** HttpOnly cookies, SameSite rules, server‑validated tokens
* **Real‑Time:** Server‑Sent Events (SSE) streaming live uptime/downtime snapshots



## Core Components

### 1. Uptime Monitoring

Executes periodic HTTP checks against configured services.

* Retries with configurable backoff
* Records response times, status codes, and failures
* Logs every check
* Creates recovery or failure events for downstream processors

### 2. SSL Certificate Monitoring

Monitors certificate expiry across monitored domains.

* Extracts issuer, SANs, fingerprint, key data
* Tracks days remaining
* Supports retrying failed checks
* Emits alert events at configurable thresholds (e.g., 30, 14, 7, 3 days)

### 3. Notification Processing

Consumes events from the event‑outbox table.

* Uses Mustache templates
* Sends email notifications
* Respects cooldown windows and retry logic
* Allows configurable sender/recipient rules

### 4. REST Endpoints

Provides endpoints for retrieving:

* Uptime logs
* SSL logs
* Service configuration
* Real‑time status stream (via SSE)

### 5. Server‑Sent Events

Continuously streams the latest combined uptime/SSL state to subscribed clients.
Ideal for dashboards needing instant updates.


## Security Model

The backend uses:

* **HttpOnly cookies** to store session tokens
* **SameSite policies** to reduce CSRF
* **Backend‑side validation** for all tokens
* **No local storage** usage for sensitive data



## Processes Summary

| Process                | Description                                                    |
| ---------------------- | -------------------------------------------------------------- |
| Uptime Check Task      | Performs HTTP uptime checks with retry logic and logs results. |
| SSL Monitor Task       | Validates certificates, calculates expiry, triggers alerts.    |
| Notification Processor | Sends templated messages based on events.                      |
| SSE Broadcaster        | Streams live status states to clients.                         |


## Deployment

SkyPulse backend is deployed on **Render**, with environment variables, database URLs, SMTP credentials, and cookie settings configured via the dashboard.
[Backend link ](https://skypulse-monitoring-system-backend.onrender.com/api/rest/system/health)


## License

Proprietary – internal use only.
