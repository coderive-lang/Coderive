package cod.parser.context;

import cod.error.ParseError;

/** Represents the result of a parsing operation, including the new parser state. */
public class ParseResult<T> {
  private final T value;
  private final ParserState state;
  private final ParseError error;

  private ParseResult(T value, ParserState state, ParseError error) {
    this.value = value;
    this.state = state;
    this.error = error;
  }

  public static <T> ParseResult<T> success(T value, ParserState state) {
    return new ParseResult<>(value, state, null);
  }

  public static <T> ParseResult<T> failure(ParseError error, ParserState state) {
    return new ParseResult<>(null, state, error);
  }

  public boolean isSuccess() {
    return error == null;
  }

  public boolean isFailure() {
    return error != null;
  }

  public T getValue() {
    if (error != null) {
      throw new IllegalStateException("Cannot get value from failed result: " + error);
    }
    return value;
  }

  public ParserState getState() {
    return state;
  }

  public ParseError getError() {
    return error;
  }

  /** Transform successful result, preserving state. */
  public <U> ParseResult<U> map(java.util.function.Function<T, U> mapper) {
    if (isFailure()) {
      return new ParseResult<>(null, state, error);
    }
    return new ParseResult<>(mapper.apply(value), state, null);
  }

  /** Chain parsing operations, passing state automatically. */
  public <U> ParseResult<U> flatMap(java.util.function.Function<T, ParseResult<U>> mapper) {
    if (isFailure()) {
      return new ParseResult<>(null, state, error);
    }
    return mapper.apply(value);
  }
}
