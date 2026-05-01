package cod.parser.context;

import cod.lexer.Token;
import cod.lexer.TokenType;
import cod.lexer.TokenType.Keyword;
import cod.lexer.TokenType.Symbol;
import cod.error.ParseError;
import static cod.semantic.ObjectValidator.is;
import static cod.semantic.ObjectValidator.nil;

import java.util.List;

public final class ParserContext {
  private ParserState state;
  private int unsafeDeclarationDepth = 0;

  public ParserContext(ParserState initialState) {
    this.state = initialState;
  }

  public ParserContext(List<Token> tokens) {
    this.state = new ParserState(tokens);
  }

  // === CORE METHODS ===

  public Token now() {
    return state.now();
  }

  public Token consume() {
    Token token = now();
    state = state.advance();
    return token;
  }

  public boolean consume(TokenType expected) {
    Token token = state.now();
    if (is(token, expected)) {
      state = state.advance();
      return true;
    }
    return false;
  }

  public Token expect(TokenType expected) throws ParseError {
    Token token = now();
    if (is(token, expected)) {
      state = state.advance();
      return token;
    }
    throw new ParseError(
        "Expected " + expected + ", got " + (nil(token) ? "EOF" : token.type)
            + (!nil(token) && token.length > 0 ? " ('" + token.getText() + "')" : ""),
        !nil(token) ? token.line : state.getLine(),
        !nil(token) ? token.column : state.getColumn());
  }

  public Token expect(Symbol expected) throws ParseError {
    Token token = state.now();
    if (is(token, expected)) {
      state = state.advance();
      return token;
    }
    throw new ParseError(
        "Expected symbol '" + expected + "', got " + (nil(token) ? "EOF" : token.type)
            + (!nil(token) && token.length > 0 ? " ('" + token.getText() + "')" : ""),
        !nil(token) ? token.line : state.getLine(),
        !nil(token) ? token.column : state.getColumn());
  }

  public Token expect(Keyword expected) throws ParseError {
    Token token = state.now();
    if (is(token, expected)) {
      state = state.advance();
      return token;
    }
    throw new ParseError(
        "Expected keyword '" + expected + "', got " + (nil(token) ? "EOF" : token.type)
            + (!nil(token) && token.length > 0 ? " ('" + token.getText() + "')" : ""),
        !nil(token) ? token.line : state.getLine(),
        !nil(token) ? token.column : state.getColumn());
  }

  // === LL(0..3) LOOKAHEAD ===

  public Token next(int offset) {
    return state.next(offset);
  }

  public Token next() {
    return next(1);
  }

  // === STATE MANAGEMENT ===

  public ParserState getState() { return state; }
  public void setState(ParserState newState) { this.state = newState; }
  public void reset() { this.state = new ParserState(state.getTokens()); }
  public void resetTo(int position) { this.state = state.withPosition(position); }

  public int getPosition() { return state.getPosition(); }
  public int getLine() { return state.getLine(); }
  public int getColumn() { return state.getColumn(); }
  public boolean hasMore() { return state.hasMore(); }
  public boolean atEOF() { return state.atEOF(); }
  public List<Token> getTokens() { return state.getTokens(); }

  public void enterUnsafeDeclaration() { unsafeDeclarationDepth++; }
  public void exitUnsafeDeclaration() { if (unsafeDeclarationDepth > 0) unsafeDeclarationDepth--; }
  public boolean isInUnsafeDeclaration() { return unsafeDeclarationDepth > 0; }

  @Override
  public String toString() { return state.toString(); }
}