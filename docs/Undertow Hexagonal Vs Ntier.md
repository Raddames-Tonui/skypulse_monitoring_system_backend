✅ **Your current Undertow setup is clearly Hexagonal Architecture — *not* N-Tier.**



### ⚙️ 1. How your current Undertow project works

| Layer                                    | Example from your code                                                    | Responsibility                                                                                      |
| ---------------------------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| **Inbound Adapter (Presentation / API)** | `RestAPIServer`, `Routes`, `GetUsers`, `CreateUser`, etc.                 | Exposes HTTP endpoints using Undertow. Handles request parsing & response formatting.               |
| **Application / Domain Logic**           | Your handlers’ logic (e.g., user creation, authentication, captcha, etc.) | Performs actual use-case logic — validation, DB calls, password hashing. No framework dependencies. |
| **Outbound Adapter (Persistence)**       | `DatabaseManager` using HikariCP                                          | Abstracts PostgreSQL — provides connections and data access.                                        |
| **Infrastructure / Configuration**       | `ConfigLoader`, `Configuration` XML, `ResponseUtil`, `CaptchaUtil`        | Cross-cutting concerns — startup, config, utilities.                                                |

---

### 🧱 2. Why It’s **Hexagonal**

| Hexagonal Principle                                  | Evidence in your setup                                                                                                                                              |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Ports define boundaries; Adapters implement them** | You interact with `DatabaseManager` (a port) instead of writing raw SQL inside handlers. Undertow handlers are input adapters.                                      |
| **Frameworks are on the edges**                      | Undertow and Hikari are isolated. None of your domain code depends on them directly.                                                                                |
| **Dependency direction points inward**               | `Main` → `RestAPIServer` → `Handlers` → `DatabaseManager` (inversion of control). The core logic never imports Undertow classes beyond the `HttpHandler` interface. |
| **Replaceable interfaces**                           | You could swap Undertow with Vert.x or Hikari with jOOQ with no major rewrite — the business logic remains.                                                         |
| **Configuration externalized**                       | Your `config.xml` defines runtime setup (server + DB) separate from code.                                                                                           |

So: **your architecture follows Ports and Adapters (Hexagonal)**.



### 🏗️ 3. Why it’s *not* N-Tier

An **N-Tier** architecture is typically **layered and hierarchical**, e.g.:

```
Controller → Service → Repository → Database
```

* Each layer strictly depends on the one below.
* Layers are rigid — controllers must call services, services must call repositories, etc.
* It’s a top-down dependency flow.

In your Undertow system:

* You don’t have mandatory “tiers.”
* Handlers directly coordinate logic using ports (like `DatabaseManager` or utilities).
* There’s no strict “service” layer between the web and DB unless you explicitly choose to add one.
* Dependencies are **radial**, not linear — which is Hexagonal.


### 🧠 4. Quick visual comparison

**N-Tier (Traditional)**

```
React → REST Controller → Service Layer → Repository → Database
```

(Downward dependency flow)

**Your Undertow (Hexagonal)**

```
React → Undertow (Input Adapter) → Handler (Core Logic)
                    ↸
                      DatabaseManager (Output Adapter)
```

(Bidirectional isolation, independent adapters)


### 🧩 5. Why this matters

| Attribute                                 | N-Tier | Your Hexagonal Setup  |
| ----------------------------------------- | ------ | --------------------- |
| Tight coupling                            | High   | Low                   |
| Replaceable frameworks                    | No     | Yes                   |
| Testability                               | Hard   | Easy                  |
| Suitable for background tasks, schedulers | Weak   | Strong                |
| Configuration-driven runtime              | Rare   | Built-in (XML config) |
| Favors domain focus                       | No     | Yes                   |


### ✅ Conclusion

> **Your current Undertow setup is Hexagonal Architecture (Ports and Adapters)**
> — specifically a **Clean, Modular Monolith** version of it.
> It is **not N-Tier**, because:
>
> * You don’t have a linear tier dependency chain.
> * Your application is organized around ports (handlers, DB manager) rather than layers.
> * Frameworks (Undertow, Hikari) live at the edges, not in the core.


Would you like me to draw a short side-by-side ASCII diagram of your exact current flow (React → Undertow → PostgreSQL) versus a typical N-Tier version so you can visually compare how control and dependencies differ?


Comparison Snapshot (Login Example)

| Stage       | **N-Tier**                        | **Hexagonal**                                     |
| ----------- | --------------------------------- | ------------------------------------------------- |
| Handler     | Calls service directly            | Calls domain *port (use case)*                    |
| Service     | Contains logic + repository calls | Contains pure logic only                          |
| Repository  | Direct DB access                  | Implements a *port* (interface defined by domain) |
| Config      | Often mixed                       | Externalized (XML/ConfigLoader)                   |
| Testability | Integration-heavy                 | Unit-testable in isolation                        |
| Coupling    | Tight (service ↔ repo)            | Loose (interface boundaries)                      |
