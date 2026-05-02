package cod.parser;

import cod.ast.ASTFactory;
import cod.semantic.NamingValidator;
import cod.error.ParseError;
import cod.ast.node.*;

import java.util.ArrayList;
import java.util.List;

import cod.lexer.Token;
import static cod.lexer.TokenType.*;
import cod.parser.context.*;

import static cod.lexer.TokenType.Symbol.*;
import static cod.lexer.TokenType.Keyword.*;

public class StatementParser extends BaseParser {

  public final ExpressionParser expressionParser;
  private final SlotParser slotParser;

  public StatementParser(ParserContext ctx, ExpressionParser expressionParser) {
    super(ctx);
    this.expressionParser = expressionParser;
    this.slotParser = new SlotParser(this);
  }

  @Override
  protected BaseParser createIsolatedParser(ParserContext isolatedCtx) {
    ExpressionParser isolatedExprParser = new ExpressionParser(
        isolatedCtx, 
        this.expressionParser.getGlobalRegistry(),
        null
    );
    StatementParser isolatedStmtParser = new StatementParser(isolatedCtx, isolatedExprParser);
    isolatedExprParser.setStatementParser(isolatedStmtParser);
    return isolatedStmtParser;
  }

  public Stmt parseStmt() {
    return parseStmtInternal();
  }

  private Stmt parseStmtInternal() {
    checkIllegalDeclaration();

    if (match(IF)) return parseIfStmt();
    if (match(FOR)) return parseForStmt();
    if (match(FIN)) return parseFinStmt();
    if (match(SKIP)) return parseSkipStmt();
    if (match(BREAK)) return parseBreakStmt();

    if (match(TRUE, LBRACE) || match(FALSE, LBRACE)) {
      throw error("Missing 'if' keyword. Use 'if " + now().getText() + " { ... }' instead.");
    }

    if (is(now(), ID)) {
      // Fast-Path LL(0..2)
      if (match(ID, DOUBLE_COLON_ASSIGN) || match(ID, COLON)) {
        return parseVariableDeclaration();
      }

      // Selective Fallback LL(0..3)
      if (match(ID, COMMA, ID)) {
        return parseReturnSlotAssignment();
      }

      if (match(ID, LBRACE)) {
        String varName = now().getText();
        if (!varName.equals("_") && Character.isLowerCase(varName.charAt(0))) {
          if (!match(ID, LBRACE, RBRACE)) {
            throw error("Unexpected block after variable '" + varName + "'. Did you mean 'if " + varName + " { ... }'?", now());
          }
        }
      }
    }

    if (match(TILDE_ARROW)) return parseSlotAssignment();

    Expr target = expressionParser.parseExpr();

    if (is(now(), ASSIGN)) {
        Token assignToken = consume();
        Expr value = expressionParser.parseExpr();
        
        if (target instanceof Identifier && "_".equals(((Identifier)target).name)) {
            throw error("Cannot assign to '_'. Underscore is reserved for discard/placeholder.");
        }
        return ASTFactory.createAsmt(target, value, false, assignToken);
    }

    return target;
  }

  private void checkIllegalDeclaration() {
    Token current = now();
    if (current.type != KEYWORD) return;

    if (is(current, IF, ELSE, ELIF, FOR, FIN, SKIP, BREAK)) return;
    if (isTypeStart(current)) return;

    if (match(ANY, ID, ASSIGN) || match(ANY, ID, LBRACKET, RBRACKET)) {
        throw error("Illegally used reserved keyword '" + current.getText() + "' for declaration.", current);
    }
  }

  //... The remainder of the parser methods (parseSkipStmt, parseForStmt, parseIfStmt, etc.) 
  // stay structurally identical because they use the fast, forward-only `expect()` and `consume()` paths.

  private Stmt parseSkipStmt() {
    Token skipToken = expect(SKIP);
    Token nextToken = now();
    if (!(any(is(nextToken, EOF), is(nextToken, RBRACE)) || isStmtStart())) {
      throw error("Nothing can follow 'skip' in the same statement. 'skip' must be the complete statement.", skipToken);
    }
    return ASTFactory.createSkipStmt(skipToken);
  }

  private Stmt parseBreakStmt() {
    Token breakToken = expect(BREAK);
    Token nextToken = now();
    if (!(any(is(nextToken, EOF), is(nextToken, RBRACE)) || isStmtStart())) {
      throw error("Nothing can follow 'break' in the same statement. 'break' must be the complete statement.", breakToken);
    }
    return ASTFactory.createBreakStmt(breakToken);
  }

  private Stmt parseSlotAssignment() {
    Token tildeArrowToken = expect(TILDE_ARROW);
    return slotParser.parseSlotAssignmentsAsStmt(tildeArrowToken);
  }

  private Stmt parseVariableDeclaration() {
    Token startToken = now();
    String typeName = null;
    boolean isImplicit = false;
    String varName = null;
    Expr value = null;

    if (is(ID)) {
      varName = expect(ID).getText();

      if (is(DOUBLE_COLON_ASSIGN)) {
        NamingValidator.validateVariableDeclarationName(varName, startToken);
        isImplicit = true;
        expect(DOUBLE_COLON_ASSIGN);
        value = expressionParser.parseExpr();

      } else if (is(COLON)) {
        NamingValidator.validateVariableDeclarationName(varName, startToken);
        expect(COLON);

        if (isTypeStart(now())) {
          typeName = parseTypeReference();
          if (consume(ASSIGN)) {
            value = expressionParser.parseExpr();
          }
        } else {
          throw error("Expected type after ':' in variable declaration.", startToken);
        }
      } else {
        throw error("Expected variable declaration in format 'name: type', 'name: type = value', or 'name := value'", startToken);
      }
    } else {
      throw error("Expected variable declaration in format 'name: type', 'name: type = value', or 'name := value'", startToken);
    }

    if (!nil(varName)) {
      NamingValidator.validateVariableOrConstantName(varName, startToken);
      if (NamingValidator.isAllCaps(varName) && value == null) {
        throw error("Constant '" + varName + "' must have an initial value", startToken);
      }
    }

    Var varNode = ASTFactory.createVar(varName, value, startToken);
    varNode.explicitType = isImplicit ? null : typeName;
    return varNode;
  }

  private Stmt parseIfStmt() {
    Token ifToken = expect(IF);
    Expr condition = expressionParser.parseExpr();
    StmtIf rootIfNode = ASTFactory.createIfStmt(condition, ifToken);

    parseControlFlowBlock(rootIfNode.thenBlock);

    StmtIf currentNode = rootIfNode;

    while (is(ELIF)) {
      Token elifToken = expect(ELIF);
      Expr elifCondition = expressionParser.parseExpr();
      StmtIf elifNode = ASTFactory.createIfStmt(elifCondition, elifToken);

      parseControlFlowBlock(elifNode.thenBlock);
      currentNode.elseBlock.statements.add(elifNode);
      currentNode = elifNode;
    }

    if (is(ELSE)) {
      expect(ELSE);

      if (is(IF)) {
        currentNode.elseBlock.statements.add(parseIfStmt());
      } else {
        if (is(LBRACE)) {
          expect(LBRACE);
          while (!is(RBRACE) && !is(EOF)) {
            currentNode.elseBlock.statements.add(parseStmtInternal());
          }
          expect(RBRACE);
        } else {
          Token next = now();
          if (!(is(next, EOF) || is(next, RBRACE)) && !isInControlFlow(next)) {
            currentNode.elseBlock.statements.add(parseStmtInternal());
          }
        }
      }
    }
    return rootIfNode;
  }

  private void parseControlFlowBlock(Block block) {
    if (is(LBRACE)) {
      expect(LBRACE);
      while (!is(RBRACE) && !is(EOF)) {
        block.statements.add(parseStmtInternal());
      }
      expect(RBRACE);
    } else {
      Token next = now();
      if (!(is(next, EOF) || is(next, RBRACE)) && !isInControlFlow(next)) {
        block.statements.add(parseStmtInternal());
      }
    }
  }

  private boolean isInControlFlow(Token token) {
    return is(token, IF, FOR, ELSE, ELIF, FIN, SKIP, BREAK);
  }

  private Stmt parseForStmt() {
    Token forToken = expect(FOR);
    Token iteratorToken = now();
    String iterator = expect(ID).getText();
    if (NamingValidator.isAllCaps(iterator)) {
      throw error("Loop iterator '" + iterator + "' cannot use ALL_CAPS (reserved for constants)", iteratorToken);
    }
    
    expect(OF);
    Expr source = expressionParser.parseExpr();

    if (is(RANGE_DOTDOT)) {
        Token rangeToken = expect(RANGE_DOTDOT);
        Expr end = expressionParser.parseExpr();
        Expr step = null;
        Token stepToken = null;
        if (is(RANGE_HASH)) {
            stepToken = expect(RANGE_HASH);
            step = parseStepExpr(iterator, iteratorToken);
        } else if (is(BY)) {
            stepToken = expect(BY);
            step = parseStepExpr(iterator, iteratorToken);
        }
        Range range = ASTFactory.createRange(step, source, end, stepToken, rangeToken);
        For forNode = ASTFactory.createFor(iterator, range, forToken, iteratorToken);
        return parseForLoopBody(forNode);
    } 
    else if (is(TO)) {
        Token rangeToken = expect(TO);
        Expr end = expressionParser.parseExpr();
        Expr step = null;
        Token stepToken = null;
        if (is(BY)) {
            stepToken = expect(BY);
            step = parseStepExpr(iterator, iteratorToken);
        }
        Range range = ASTFactory.createRange(step, source, end, stepToken, rangeToken);
        For forNode = ASTFactory.createFor(iterator, range, forToken, iteratorToken);
        return parseForLoopBody(forNode);
    } 
    else {
        For forNode = ASTFactory.createFor(iterator, source, forToken, iteratorToken);
        return parseForLoopBody(forNode);
    }
  }

  private Expr parseStepExpr(String iterator, Token iteratorToken) {
    if (is(MUL) || is(DIV)) {
      Token operatorToken = now();
      String operator = operatorToken.getText();
      consume();
      Expr operand = expressionParser.parseExpr();
      Expr iteratorRef = ASTFactory.createIdentifier(iterator, iteratorToken);
      return ASTFactory.createBinaryOp(iteratorRef, operator, operand, operatorToken);
    } else if (is(PLUS) || is(MINUS)) {
      Token operatorToken = now();
      consume();
      if (is(INT_LIT, FLOAT_LIT)) {
        Expr operand = expressionParser.parsePrimaryExpr();
        if (is(operatorToken, PLUS)) {
          return operand;
        }
        return ASTFactory.createUnaryOp("-", operand, operatorToken);
      } else {
        return expressionParser.parseExpr();
      }
    } else {
      return expressionParser.parseExpr();
    }
  }

  private Stmt parseForLoopBody(For forNode) {
    parseControlFlowBlock(forNode.body);
    return forNode;
  }

  private Stmt parseFinStmt() {
    Token finToken = expect(FIN);
    return ASTFactory.createFin(finToken);
  }

  private Stmt parseReturnSlotAssignment() {
    List<String> varNames = parseIdList();

    Token assignToken = null;
    if (is(DOUBLE_COLON_ASSIGN)) {
        assignToken = expect(DOUBLE_COLON_ASSIGN);
    } else {
        assignToken = expect(ASSIGN);
    }

    List<String> slotNames = expressionParser.parseReturnSlots();
    expect(COLON);
    
    if (expressionParser.isLambdaExpression()) {
        Lambda lambda = expressionParser.parseLambdaSignature();
        if (!lambda.returnSlots.isEmpty() && lambda.returnSlots.size() != slotNames.size()) {
            throw error("Number of slot names (" + slotNames.size() + ") does not match number of lambda return slots (" + lambda.returnSlots.size() + ")", assignToken);
        }
        return ASTFactory.createReturnSlotAsmt(varNames, lambda, assignToken);
    }
    
    MethodCall methodCall = expressionParser.parseMethodCall();
    methodCall.slotNames = slotNames;

    if (varNames.size() != slotNames.size()) {
        throw error("Number of variables (" + varNames.size() + ") does not match number of slots (" + slotNames.size() + ")");
    }

    return ASTFactory.createReturnSlotAsmt(varNames, methodCall, assignToken);
  }

  private List<String> parseIdList() {
    List<String> ids = new ArrayList<String>();
    ids.add(expect(ID).getText());
    while (consume(COMMA)) {
      ids.add(expect(ID).getText());
    }
    return ids;
  }
}
