public void handleRequest(HttpServerExchange exchange) throws Exception {
    Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
    if (body == null) return;

    Long groupId = HttpRequestUtil.getLong(body, "contact_group_id");
    var members = (List<Integer>) body.get("members_ids");

    if (groupId == null || members == null || members.isEmpty()) {
        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing fields");
        return;
    }

    try (Connection conn = DatabaseManager.getDataSource().getConnection()) {
        conn.setAutoCommit(false);

        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO contact_group_members (contact_group_id, user_id, is_primary)
            VALUES (?, ?, FALSE)
            ON CONFLICT (contact_group_id, user_id) DO NOTHING;
        """);

        for (Integer id : members) {
            ps.setLong(1, groupId);
            ps.setInt(2, id);
            ps.addBatch();
        }

        ps.executeBatch();
        conn.commit();

        ResponseUtil.sendSuccess(exchange, "Members added", null);

    } catch (Exception e) {
        ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
