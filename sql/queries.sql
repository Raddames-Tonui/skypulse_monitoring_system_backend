SELECT task_name, status, last_run_at, next_run_at, error_message
FROM background_tasks
ORDER BY last_run_at DESC;
