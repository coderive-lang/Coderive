package cod.parser;

import cod.ast.SourceSpan;
import cod.error.ParseError;
import cod.lexer.Token;
import cod.lexer.TokenType;
import static cod.lexer.TokenType.*;
import cod.parser.context.*;
import cod.lexer.TokenType.Keyword;
import static cod.lexer.TokenType.Keyword.*;
import cod.lexer.TokenType.Symbol;
import static cod.lexer.TokenType.Symbol.*;
import cod.semantic.ObjectValidator;

import java.util.List;

public abstract class BaseParser {
  protected final ParserContext ctx;
  protected final List<Token> tokens;

  // Common Modifiers for Match Windows
  protected static final Object[] VISIBILITY = {SHARE, LOCAL};
  protected static final Object[] MODIFIERS = {SHARE, LOCAL, UNSAFE, BUILTIN, POLICY};

  public BaseParser(ParserContext ctx) {
    this.ctx = ctx;
    this.tokens = ctx.getTokens();
  }

  // === LL(0..3) Window Matching ===

  protected boolean match(Object... patterns) {
    if (patterns.length > 4) {
      throw new IllegalArgumentException("LL(k) window size limit exceeded.");
    }
    for (int i = 0; i < patterns.length; i++) {
      Token t = (i == 0) ? now() : next(i);
      Object pattern = patterns[i];
      if (pattern == null) continue; // null acts as wildcard ANY
      if (!matchSingle(t, pattern)) return false;
    }
    return true;
  }

  private boolean matchSingle(Token t, Object pattern) {
    if (t == null) return false;
    if (pattern instanceof Symbol) return ObjectValidator.is(t, (Symbol) pattern);
    if (pattern instanceof Keyword) return ObjectValidator.is(t, (Keyword) pattern);
    if (pattern instanceof TokenType) return ObjectValidator.is(t, (TokenType) pattern);
    if (pattern instanceof Object[]) {
      for (Object p : (Object[]) pattern) {
        if (matchSingle(t, p)) return true;
      }
      return false;
    }
    return false;
  }

  // Fast token checks
  protected boolean is(Symbol... sb) { return ObjectValidator.is(now(), sb); }
  protected boolean is(Keyword... kw) { return ObjectValidator.is(now(), kw); }
  protected boolean is(TokenType... type) { return ObjectValidator.is(now(), type); }
  protected boolean is(Token tk, Symbol... sb) { return ObjectValidator.is(tk, sb); }
  protected boolean is(Token tk, Keyword... kw) { return ObjectValidator.is(tk, kw); }
  protected boolean is(Token tk, TokenType... type) { return ObjectValidator.is(tk, type); }
  protected boolean any(boolean... values) { return ObjectValidator.any(values); }
  protected boolean nil(Object... obj) { return ObjectValidator.nil(obj); }
  
  protected boolean matches(String expected) {
    Token t = now();
    return t != null && t.matches(expected);
  }

  protected boolean matchesIgnoreCase(String expected) {
    Token t = now();
    return t != null && t.matchesIgnoreCase(expected);
  }

  protected String getText() {
    Token t = now();
    return t != null ? t.getText() : "";
  }

  protected Token expect(TokenType expectedType) { return ctx.expect(expectedType); }
  protected Token expect(Symbol expectedSymbol) { return ctx.expect(expectedSymbol); }
  protected Token expect(Keyword expectedKeyword) { return ctx.expect(expectedKeyword); }

  protected BaseParser createIsolatedParser(ParserContext isolatedCtx) {
    return new BaseParser(isolatedCtx) {};
  }

  protected boolean isClassStartWithoutModifier() {
    if (!is(now(), ID)) return false;
    String name = now().getText();
    if (name.length() == 0 || !Character.isUpperCase(name.charAt(0))) return false;

    if (match(ID, WITH, ID, LBRACE)) return true;
    if (match(ID, LBRACE)) return true;
    if (match(ID, IS, ID, LBRACE)) return true;
    
    return false;
  }

  protected Token now() { return ctx.now(); }
  protected Token consume() { return ctx.consume(); }
  protected boolean consume(TokenType type) { return ctx.consume(type); }
  protected Token next(int offset) { return ctx.next(offset); }
  protected Token next() { return ctx.next(); }

  protected boolean consume(Symbol expectedSymbol) {
    if (is(expectedSymbol)) {
      consume();
      return true;
    }
    return false;
  }

  protected ParseError error(String message) {
    Token now = now();
    if (now != null) return error(message, now);
    return new ParseError(message, ctx.getLine(), ctx.getColumn());
  }

  protected ParseError error(String message, Token token) {
    if (token != null) return new ParseError(message, token);
    return new ParseError(message, ctx.getLine(), ctx.getColumn());
  }

  protected ParseError error(String message, Token startToken, Token endToken) {
    if (startToken != null && endToken != null) {
      return new ParseError(message, startToken, endToken);
    } else if (startToken != null) {
      return error(message, startToken);
    }
    return error(message);
  }

  protected ParseError errorWithSpan(String message, SourceSpan span) {
    if (span != null) return new ParseError(message, span);
    return error(message);
  }

  protected String getTypeName(TokenType type) { return type.toString(); }

  protected boolean isTypeStart(Token token) {
    return any(
        is(token, INT, TEXT, FLOAT, BOOL, TYPE, I8, I16, I32, I64, U8, U16, U32, U64, F32, F64),
        is(token, ID),
        is(token, LPAREN, LBRACKET, MUL));
  }

  protected boolean isUnsafeTypeContext() { return ctx.isInUnsafeDeclaration(); }

  protected boolean isUnsafeNumericTypeKeyword(Token token) {
    return is(token, I8, I16, I32, I64, U8, U16, U32, U64, F32, F64);
  }

  protected boolean isUnsafeNumericTypeName(String typeName) {
    if (typeName == null) return false;
    return typeName.equals("i8") || typeName.equals("i16") || typeName.equals("i32") || typeName.equals("i64") || typeName.equals("u8") || typeName.equals("u16") || typeName.equals("u32") || typeName.equals("u64") || typeName.equals("f32") || typeName.equals("f64");
  }

  protected String parseQualifiedName() {
    StringBuilder name = new StringBuilder();
    name.append(expect(ID).getText());
    while (consume(DOT)) {
      name.append(".");
      Token next = now();
      if (is(next, ID)) {
        name.append(expect(ID).getText());
      } else if (canBeMethod(next)) {
        name.append(expect(KEYWORD).getText());
      } else {
        throw error("Expected identifier or method keyword after '.'");
      }
    }
    return name.toString();
  }

  protected boolean canBeMethod(Token token) {
    return is(token, OF, ALL, ANY, GET, SET, INT, TEXT, FLOAT, BOOL, TYPE);
  }

  protected String parseTypeReference() {
    StringBuilder type = new StringBuilder();
    if (is(MUL)) {
      Token pointerToken = expect(MUL);
      if (!isUnsafeTypeContext()) {
        throw error("Pointer types can only be used inside an unsafe class or method", pointerToken);
      }
      type.append("*").append(parseTypeReference());
    } else if (is(LBRACKET)) {
      expect(LBRACKET);
      if (is(RBRACKET)) {
        expect(RBRACKET);
        type.append("[]");
      } else {
        String inner = parseTypeReference();
        expect(RBRACKET);
        type.append("[").append(inner).append("]");
      }
    } else if (is(LPAREN)) {
      type.append(parseGroupedType());
    } else {
      Token typeToken = now();
      if (isTypeStart(typeToken) && !is(typeToken, LBRACKET)) {
        String typeName = consume().getText();
        if (isUnsafeNumericTypeName(typeName) && !isUnsafeTypeContext()) {
          throw error("Unsafe type '" + typeName + "' can only be used inside an unsafe class or method", typeToken);
        }
        type.append(typeName);
      } else {
        throw error("Expected type name");
      }
    }

    while (is(LBRACKET)) {
      Token lbracketToken = expect(LBRACKET);
      if (!isUnsafeTypeContext()) {
        throw error("Sized array types can only be used inside an unsafe class or method", lbracketToken);
      }
      if (is(INT_LIT)) {
        type.append("[").append(expect(INT_LIT).getText()).append("]");
      } else {
        type.append("[]");
      }
      expect(RBRACKET);
    }

    if (consume(QUESTION)) {
      return type.toString() + "|none";
    }

    while (is(PIPE)) {
      expect(PIPE);
      type.append("|").append(parseTypeReference());
    }

    return type.toString();
  }

  private String parseGroupedType() {
    expect(LPAREN);
    StringBuilder group = new StringBuilder("(");
    group.append(parseTypeReference());
    while (consume(COMMA)) {
      group.append(",").append(parseTypeReference());
    }
    expect(RPAREN);
    group.append(")");
    return group.toString();
  }

  public int getPosition() { return ctx.getPosition(); }
  public int getLine() { return ctx.getLine(); }
  public int getColumn() { return ctx.getColumn(); }
  public ParserState getCurrentState() { return ctx.getState(); }
  public void setState(ParserState state) { ctx.setState(state); }

  protected boolean isExprStart(Token t) {
    if (nil(t)) return false;
    if (is(t, WS, LINE_COMMENT, BLOCK_COMMENT)) return false;
    return any(
        is(t, INT_LIT, FLOAT_LIT, TEXT_LIT, BOOL_LIT, ID),
        is(t, LPAREN, LBRACKET, BANG, PLUS, MINUS, DOLLAR, AMPERSAND, MUL),
        is(t, NONE, TRUE, FALSE, SUPER, THIS));
  }

  protected boolean isClassStart() {
    if (is(SHARE, LOCAL, UNSAFE)) return true;
    return isClassStartWithoutModifier();
  }

  protected boolean isStmtStart() {
    Token token = now();
    if (token == null) return false;
    if (is(token, IF, FOR, EXIT, SKIP, BREAK)) return true;
    if (is(token, TILDE_ARROW)) return true;

    if (is(token, ID)) {
      String text = token.getText();
      if (text != null && text.length() > 0) {
        char first = text.charAt(0);
        if (Character.isLowerCase(first)) return true;
        if (Character.isUpperCase(first)) {
          if (match(ID, DOUBLE_COLON_ASSIGN) || match(ID, COLON)) return true;
          return false;
        }
        return true;
      }
    }
    return false;
  }
}