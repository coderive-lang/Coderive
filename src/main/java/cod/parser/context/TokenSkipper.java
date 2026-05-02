package cod.parser.context;

import cod.lexer.Token;
import cod.lexer.TokenType;
import static cod.lexer.TokenType.*;
import cod.lexer.TokenType.Keyword;
import static cod.lexer.TokenType.Keyword.*;
import cod.lexer.TokenType.Symbol;
import static cod.lexer.TokenType.Symbol.*;
import cod.semantic.ObjectValidator;

import java.util.List;

public class TokenSkipper {

  private final ParserContext ctx;

  public TokenSkipper(List<Token> tokens) {
    this.ctx = new ParserContext(tokens);
  }

  public ParserContext ctx() { return ctx; }
  public Token consume() { return ctx.consume(); }
  public Token now() { return ctx.now(); }

  protected boolean is(Token tk, Symbol... sb) { return ObjectValidator.is(tk, sb); }
  protected boolean is(Token tk, Keyword... kw) { return ObjectValidator.is(tk, kw); }
  protected boolean is(Token tk, TokenType... type) { return ObjectValidator.is(tk, type); }
  protected boolean any(boolean... values) { return ObjectValidator.any(values); }

  private boolean is(Symbol... sb) { return is(now(), sb); }
  private boolean is(Keyword... kw) { return is(now(), kw); }
  private boolean is(TokenType... type) { return is(now(), type); }

  public Token expect(Keyword kw) { return ctx.expect(kw); }

  protected boolean consume(Symbol expectedSymbol) {
    if (is(expectedSymbol)) {
      consume();
      return true;
    }
    return false;
  }

  public void until(Symbol symbol) {
    while (!is(EOF) && !is(symbol)) {
      Token t = now();
      if (is(t, LBRACE, LPAREN, LBRACKET)) {
        consume();
        untilMatching(t.symbol);
      } else {
        consume();
      }
    }
    if (is(symbol)) consume();
  }

  public void untilMatching(Symbol opening) {
    Symbol closing;
    if (is(opening, LBRACE)) closing = RBRACE;
    else if (is(opening, LPAREN)) closing = RPAREN;
    else if (is(opening, LBRACKET)) closing = RBRACKET;
    else return;

    until(closing);
  }

  public void untilStmtEnd() {
    while (!is(EOF)) {
      Token t = now();

      if (any(is(t, RBRACE), is(t, ELSE, ELIF, IF, FOR, FIN))) {
        break;
      }

      if (is(t, LBRACE, LPAREN, LBRACKET)) {
        consume();
        untilMatching(t.symbol);
      } else {
        consume();
      }
    }
  }

  public void typeRef() {
    if (is(LBRACKET)) {
      ctx.expect(LBRACKET);
      if (!is(RBRACKET)) {
        typeRef();
      }
      ctx.expect(RBRACKET);
    } else if (is(LPAREN)) {
      ctx.expect(LPAREN);
      typeRef();
      while (consume(COMMA)) {
        typeRef();
      }
      ctx.expect(RPAREN);
    } else if (isTypeStart(now())) {
      consume();
    }

    while (consume(PIPE)) {
      typeRef();
    }
  }

  public void expr() {
    int braceDepth = 0;
    int parenDepth = 0;
    int bracketDepth = 0;

    while (!is(EOF)) {
      Token t = now();

      if (is(t, LBRACE)) braceDepth++;
      else if (is(t, RBRACE)) braceDepth--;
      else if (is(t, LPAREN)) parenDepth++;
      else if (is(t, RPAREN)) parenDepth--;
      else if (is(t, LBRACKET)) bracketDepth++;
      else if (is(t, RBRACKET)) bracketDepth--;

      if (braceDepth < 0 || parenDepth < 0 || bracketDepth < 0) {
        return;
      }

      if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
        if (is(t, COMMA)) return;
        if (is(t, IF, FOR, FIN, ELSE, ELIF, SHARE, LOCAL, UNIT)) return;
      }

      consume();
    }
  }

  public void methodDecl() {
    boolean isBuiltin = false;
    if (is(BUILTIN)) {
      isBuiltin = true;
      consume();
    }

    if (is(SHARE, LOCAL)) {
      consume();
    }

    if (is(ID) || canBeMethod(now())) {
      consume();
    }

    if (is(LPAREN)) {
      ctx.expect(LPAREN);
      until(RPAREN);
    }

    if (is(DOUBLE_COLON)) {
      ctx.expect(DOUBLE_COLON);
      slotContract();
    }

    if (isBuiltin) {
      return;
    }

    if (is(TILDE_ARROW)) {
      ctx.expect(TILDE_ARROW);
      while (!is(EOF) && !is(COMMA) && !isStmtEnd()) {
        if (is(LBRACE)) {
          ctx.expect(LBRACE);
          until(RBRACE);
        } else {
          consume();
        }
      }
    } else if (is(LBRACE)) {
      ctx.expect(LBRACE);
      until(RBRACE);
    }
  }

  public void slotAsmt() {
    if (is(ID)) {
      Token next = ctx.next(); // LL(0..1)
      if (is(next, COLON)) {
        consume();
        consume();
      }
    }
    expr();
  }

  private boolean isStmtEnd() {
    Token t = now();
    return any(is(t, RBRACE), is(t, ELSE, ELIF, IF, FOR, FIN), is(t, EOF));
  }

  public void slotContract() {
    do {
      if (is(ID) && is(ctx.next(), COLON)) {
        consume();
        ctx.expect(COLON);
      }
      typeRef();
    } while (consume(COMMA));
  }

  public void stmt() {
    Token current = now(); 
    if (is(current, IF)) {
      ifStmt();
    } else if (is(current, FOR)) {
      forStmt();
    } else if (is(current, FIN)) {
      consume();
    } else if (is(current, SHARE, LOCAL)) {
      if (is(current, ID)) {
        if (is(current, LPAREN)) {
          methodDecl();
          return;
        }
      }
      consume();
    } else if (is(current, ID)) {
      untilStmtEnd();
    } else {
      consume();
    }
  }

  public void forStmt() {
    ctx.expect(FOR);
    if (is(ID)) consume();

    if (is(BY)) {
      ctx.expect(BY);
      expr();
      ctx.expect(OF);
    } else if (is(RANGE_HASH)) {
      ctx.expect(RANGE_HASH);
      expr();
    }

    expr(); 

    if (is(RANGE_DOTDOT)) {
      ctx.expect(RANGE_DOTDOT);
      expr(); 
      if (is(RANGE_HASH)) {
        ctx.expect(RANGE_HASH);
        expr();
      }
    } else if (is(TO)) {
      ctx.expect(TO);
      expr(); 
    }

    stmtOrBlock();
  }

  public void ifStmt() {
    ctx.expect(IF);
    expr();
    stmtOrBlock();

    while (is(ELIF, ELSE)) {
      Token elifElseToken = now();
      consume();
      if (is(elifElseToken, ELSE) && is(IF)) {
        ctx.expect(IF);
        expr();
      } else if (is(elifElseToken, ELIF)) {
        expr();
      }
      stmtOrBlock();
    }
  }

  public void policyDecl() {
    Token current = now();
    if (is(current, SHARE, LOCAL)) {
      ctx.consume();
    }

    current = now();
    if (is(current, POLICY)) {
      ctx.consume();
    }

    if (is(ID)) {
      ctx.consume();
    }

    current = now();
    while (is(current, WITH)) {
      ctx.consume();
      hasQualifiedName();
      while (is(COMMA)) {
        ctx.consume();
        hasQualifiedName();
      }
      current = now();
    }

    if (is(LBRACE)) {
      ctx.consume();
    } else {
      return;
    }

    int braceDepth = 1;
    while (!is(EOF) && braceDepth > 0) {
      Token t = now();
      if (is(t, LBRACE)) braceDepth++;
      else if (is(t, RBRACE)) braceDepth--;
      else if (braceDepth == 1) {
        if (isPolicyMethod()) {
          policyMethodDecl();
          continue;
        }
      }
      ctx.consume();
    }
  }

  public boolean canBeMethod(Token t) {
    return is(t, OF, ALL, ANY, GET, SET, INT, TEXT, FLOAT, BOOL, TYPE);
  }

  public boolean isPolicyMethod() {
    Token current = now();
    boolean isValidName = is(current, ID) || canBeMethod(current);
    if (!isValidName) return false;

    // Fast LL(0..1) check directly over the index
    Token next = ctx.next();
    return next != null && is(next, LPAREN);
  }

  public void typeDecl() {
    Token current = now();
    if (is(current, SHARE, LOCAL)) {
      ctx.consume();
    }

    if (is(ID)) {
      ctx.consume();
    }

    current = now();
    if (is(current, IS)) {
      ctx.consume();
      hasQualifiedName();
    }

    current = now();
    while (is(current, WITH)) {
      ctx.consume();
      hasQualifiedName();
      while (is(COMMA)) {
        ctx.consume();
        hasQualifiedName();
      }
      current = now();
    }

    if (is(LBRACE)) {
      ctx.consume();
    } else {
      return;
    }

    int braceDepth = 1;
    while (!is(EOF) && braceDepth > 0) {
      Token t = now();
      if (is(t, LBRACE)) braceDepth++;
      else if (is(t, RBRACE)) braceDepth--;
      ctx.consume();
    }
  }

  public void policyMethodDecl() {
    if (isPolicyMethod()) {
      ctx.consume();
      if (is(LPAREN)) {
        ctx.consume(); 
        until(RPAREN);
      }

      if (is(DOUBLE_COLON)) {
        ctx.consume();
        typeRef();
        while (is(COMMA)) {
          ctx.consume();
          typeRef();
        }
      }
    }
  }

  public void stmtOrBlock() {
    if (is(LBRACE)) {
      ctx.expect(LBRACE);
      until(RBRACE);
    } else {
      stmt();
    }
  }

  public void hasQualifiedName() {
    if (is(ID)) {
      ctx.consume();
      while (is(DOT)) {
        ctx.consume();
        if (is(ID)) {
          ctx.consume();
        } else {
          break;
        }
      }
    }
  }

  protected boolean isTypeStart(Token token) {
    return any(is(token, INT, TEXT, FLOAT, BOOL, TYPE), is(token, ID), is(token, LPAREN, LBRACKET));
  }
}
