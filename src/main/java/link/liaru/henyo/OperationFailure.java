package link.liaru.henyo;

/** Structured failure without private request content. */
final class OperationFailure extends RuntimeException {
    final String code;
    final int status;
    final String outcome;

    OperationFailure(String code, int status, String outcome) {
        super(code);
        this.code = code;
        this.status = status;
        this.outcome = outcome;
    }
}
