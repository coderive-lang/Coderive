package cod.interpreter.exception;

@SuppressWarnings("serial")
public class EarlyFinException extends RuntimeException {
    public EarlyFinException() {
      super("Early fin");
    }
  }
