package link.liaru.henyo;

/** Process-local task identity; completion never creates or replaces a task. */
final class TaskLifecycle {
    static final class Token {
        final String owner, id;
        final long generation;
        final boolean completed;
        Token(String owner, String id, long generation, boolean completed) {
            this.owner = owner; this.id = id; this.generation = generation; this.completed = completed;
        }
    }

    private long generation;
    private Token current;

    synchronized boolean isEmpty() { return current == null; }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    synchronized Token start(String owner, String id) {
        if (current != null && !current.completed && current.owner.equals(owner) && current.id.equals(id)) return current;
        current = new Token(owner, id, ++generation, false);
        return current;
    }

    synchronized Token active(String owner) {
        return current != null && !current.completed && current.owner.equals(owner) ? current : null;
    }

    synchronized boolean completed(String owner, String id) {
        Token token = matching(owner, id);
        return token != null && token.completed;
    }

    synchronized Token matching(String owner, String id) {
        return current != null && current.owner.equals(owner) && current.id.equals(id) ? current : null;
    }

    synchronized Token finish(String owner, String id) {
        if (matching(owner, id) == null) return null;
        if (!current.completed) current = new Token(owner, id, ++generation, true);
        return current;
    }

    synchronized boolean isCurrent(Token token) { return token != null && current == token; }

    synchronized void invalidate(String owner) {
        if (current != null && (owner == null || current.owner.equals(owner))) {
            current = null;
            generation++;
        }
    }
}
