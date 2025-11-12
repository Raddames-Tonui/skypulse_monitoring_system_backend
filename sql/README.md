docker run --name skypulse-database `
  -e POSTGRES_USER=spadmin `
  -e POSTGRES_PASSWORD=skyp@lse!2020 `
  -e POSTGRES_DB=skypulse_monitoring_system_database `
  -v "$env:USERPROFILE\docker\skypulse_db\data:/var/lib/postgresql\data" `
  -p 5434:5432 `
  -d postgres:latest



mvn clean package
java -jar target/skyworld-project-java-1.0.0-SNAPSHOT.jar src/main/resources/config.xml



| Category                   | Tables                                                                                                               |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Users & Security**       | `users`, `roles`, `permissions`, `role_permissions`, `user_permissions`                                              |
| **Notifications**          | `contact_groups`, `contact_group_members`, `notification_templates`, `notification_history`, `notification_channels` |
| **Monitoring Core**        | `monitored_services`, `uptime_logs`, `ssl_logs`, `incidents`, `maintenance_windows`                                  |
| **Reporting**              | `uptime_summaries`, `system_health_logs`                                                                             |
| **Dynamic Forms (Future)** | `form_definitions`, `form_fields`, `form_layouts`, `form_audit_log`                                                  |
| **Auditing & Settings**    | `audit_log`, `system_settings`                                                                                       |


