package cod.parser.context;

import cod.lexer.Token;
import java.util.List;
import java.util.Objects;

public final class ParserState {
  private final List<Token> tokens;
  private final int position;
  private final int line;
  private final int column;

  private transient Token currentTokenCache;

  public ParserState(List<Token> tokens) {
    this(tokens, 0, 1, 1);
  }

  private ParserState(List<Token> tokens, int position, int line, int column) {
    this.tokens = Objects.requireNonNull(tokens, "tokens cannot be null");
    this.position = position;
    this.line = line;
    this.column = column;
    updateCurrentTokenCache();
  }

  private void updateCurrentTokenCache() {
    if (position >= 0 && position < tokens.size()) {
      currentTokenCache = tokens.get(position);
    } else {
      currentTokenCache = null;
    }
  }

  /**
   * Linear forward-only advancement.
   * Optimized to avoid pipeline stalls on ARM CPUs.
   */
  public ParserState advance() {
    if (position >= tokens.size()) {
      return this;
    }

    Token current = now();
    if (current == null) {
      return this;
    }

    int newPosition = position + 1;
    int newLine = current.line;
    int newColumn = current.column + current.length;

    // Predictive update for the next cursor position
    if (newPosition < tokens.size()) {
      Token nextToken = tokens.get(newPosition);
      newLine = nextToken.line;
      newColumn = nextToken.column;
    }

    return new ParserState(tokens, newPosition, newLine, newColumn);
  }

  /**
   * LL(k) Window Peek.
   * Accesses the token list directly by index for maximum throughput.
   */
  public Token next(int offset) {
    int targetPos = position + offset;
    return (targetPos >= 0 && targetPos < tokens.size()) ? tokens.get(targetPos) : null;
  }

  public Token now() { return currentTokenCache; }
  public boolean hasMore() { return position < tokens.size(); }
  public boolean atEOF() { return !hasMore(); }
  public List<Token> getTokens() { return tokens; }
  public int getPosition() { return position; }
  public int getLine() { return line; }
  public int getColumn() { return column; }

  // Method to jump to specific positions (used primarily by the MainParser router)
  public ParserState withPosition(int newPosition) {
    if (newPosition < 0 || newPosition > tokens.size()) {
      throw new IllegalArgumentException("Invalid position: " + newPosition);
    }
    return new ParserState(tokens, newPosition, 1, 1); // Line/Col will be re-synced on next access
  }

  @Override
  public String toString() {
    Token current = now();
    return String.format("ParserState[pos=%d, current=%s]", 
        position, current != null ? "'" + current.getText() + "'" : "EOF");
  }
}