## Template Syntax Documentation

### Purpose

The `template_syntax` column defines **how notification templates are interpreted and rendered**. It tells the backend which templating language or placeholder format to use when generating notifications such as emails, SMS, or PDF reports.

---

### Column Definition

```sql
template_syntax VARCHAR(20) DEFAULT 'mustache' -- Defines placeholder format
```

This field acts as a rendering **mode flag**, guiding your backend on how to substitute dynamic variables or treat the content.

---

## Supported Syntax Types

### 1. `mustache` (Modern Default)

**Placeholder Style:**

```text
Hello {{user.first_name}}, your service {{service.name}} is DOWN.
```

**Example Data:**

```json
{
  "user": { "first_name": "Alice" },
  "service": { "name": "API Gateway" }
}
```

**Rendered Output:**

```
Hello Alice, your service API Gateway is DOWN.
```

**Use Cases:**

* HTML or plain-text emails
* PDF reports
* Readable and nested placeholders

**Why it's default:** Human-readable, safe, and works with Mustache, Handlebars, or Liquid engines.

---

### 2. `positional` (Legacy / SMS Style)

**Placeholder Style:**

```text
Hello %1, your service %2 is %3.
```

**Example Data:**

```json
["Alice", "API Gateway", "DOWN"]
```

**Rendered Output:**

```
Hello Alice, your service API Gateway is DOWN.
```

**Use Cases:**

* SMS notifications
* Legacy systems or external APIs using ordered placeholders

**Why supported:** Some gateways only accept positional arguments.

---

### 3. `html` (Static or Pre-rendered Templates)

**Placeholder Style:**

```html
<html>
  <body>
    <h2>Uptime Report</h2>
    <p>Your service is fully operational.</p>
  </body>
</html>
```

**Use Cases:**

* Fully static HTML emails
* Templates pre-rendered by another engine
* High-performance static notifications

**Why included:** Allows sending content directly without rendering.

---

## Backend Rendering Logic Example

```java
switch (templateSyntax) {
    case "mustache":
        rendered = mustacheEngine.render(template, contextMap);
        break;
    case "positional":
        rendered = MessageFormat.format(template, argsArray);
        break;
    case "html":
    default:
        rendered = template; // send as-is
}
```

This approach lets different notifications use different syntaxes within the same system.

| Event           | Syntax       | Example                                               |
| --------------- | ------------ | ----------------------------------------------------- |
| `service_down`  | `mustache`   | `Dear {{user.first_name}}, {{service.name}} is down.` |
| `sms_alert`     | `positional` | `Service %1 is %2.`                                   |
| `weekly_report` | `html`       | `<h1>Weekly Uptime Report</h1>`                       |

---

## Summary

| Syntax Type  | Placeholder Format | Used For                      | Rendering Style        |
| ------------ | ------------------ | ----------------------------- | ---------------------- |
| `mustache`   | `{{variable}}`     | HTML emails, PDFs, dashboards | JSON → template engine |
| `positional` | `%1 %2 %3`         | SMS, legacy integrations      | Array substitution     |
| `html`       | `<html>...</html>` | Static HTML emails, reports   | Send as-is             |

---

### Future Extensions

You can extend this concept later to support:

* `markdown` → Slack/Telegram messages
* `liquid` or `handlebars` → Shopify-style templates
* `custom` → In-house DSL for special formatting

---

### Recommendation

Keep `mustache` as your default syntax for modern systems. It’s the most flexible and integrates cleanly with JSON-based data models, making previews, PDFs, and notifications consistent across all channels.
