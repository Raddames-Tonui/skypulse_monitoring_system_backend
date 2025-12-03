# Skypulse PostgreSQL Setup Guide

This guide walks you through setting up a PostgreSQL database for Skypulse, creating necessary tables, and seeding data.

## 1. Install PostgreSQL

You can use the default PostgreSQL installation or run it via Docker.

### Using Docker (Recommended)

```powershell
docker run --name skypulse-ms-database `
  -e POSTGRES_USER=spadmin `
  -e POSTGRES_PASSWORD=skyp@lse!2020 `
  -e POSTGRES_DB=skypulse_monitoring_system_database `
  -v "$env:USERPROFILE\docker\skypulse_db\data:/var/lib/postgresql/data" `
  -p 5432:5432 `
  -d postgres:latest
```

### Using Default PostgreSQL

1. Download and install PostgreSQL from [https://www.postgresql.org/download/](https://www.postgresql.org/download/).
2. Create a database user and database:

```sql
-- Connect to psql as default postgres user
CREATE USER spadmin WITH PASSWORD 'skyp@lse!2020';
CREATE DATABASE skypulse_monitoring_system_database;
GRANT ALL PRIVILEGES ON DATABASE skypulse_monitoring_system_database TO spadmin;
```

## 2. Create Tables

After the PostgreSQL server is up and running:

1. Navigate to the `Database_setup` directory.
2. Execute the `SkyPulse-DDL.sql` script to create all required tables:

```bash
psql -U spadmin -d skypulse_monitoring_system_database -f Database_setup/SkyPulse_DDL.sql
```

## 3. Seed Data

### Production Data

Run the following to seed the database with production data:

```bash
psql -U spadmin -d skypulse_monitoring_system_database -f Database_setup/SkyPulse_Production_seed_data.sql
```

### Sample Data (Optional)

For testing or demo purposes, you can use sample seed data:

```bash
psql -U spadmin -d skypulse_monitoring_system_database -f Database_setup/SkyPulse_Sample_Seed_Data.sql
```

## 4. Verification

1. Connect to the database:

```bash
psql -U spadmin -d skypulse_monitoring_system_database
```

2. List tables:

```sql
\dt
```

3. Check seeded data:

```sql
SELECT * FROM users LIMIT 5;
```

## 5. Notes

* Ensure the PostgreSQL port `5432` is not blocked by a firewall.
* If using Docker, data is persisted in `$env:USERPROFILE\docker\skypulse_db\data`.
* Always use strong passwords for database users.
* Use the production seed data for real deployments; sample data is only for testing.

Once PostgreSQL is set up and seeded, Skypulse services can connect and operate normally.


| Category                   | Tables                                                                                                               |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Users & Security**       | `users`, `roles`, `permissions`, `role_permissions`, `user_permissions`                                              |
| **Notifications**          | `contact_groups`, `contact_group_members`, `notification_templates`, `notification_history`, `notification_channels` |
| **Monitoring Core**        | `monitored_services`, `uptime_logs`, `ssl_logs`, `incidents`, `maintenance_windows`                                  |
| **Reporting**              | `uptime_summaries`, `system_health_logs`                                                                             |
| **Dynamic Forms (Future)** | `form_definitions`, `form_fields`, `form_layouts`, `form_audit_log`                                                  |
| **Auditing & Settings**    | `audit_log`, `system_settings`                                                                                       |


