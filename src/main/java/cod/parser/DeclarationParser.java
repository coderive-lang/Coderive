package cod.parser;

import cod.ast.ASTFactory;
import cod.ast.node.*;
import cod.error.ParseError;
import cod.parser.context.*;
import cod.semantic.ImportResolver;
import cod.semantic.NamingValidator;
import cod.semantic.PolicyValidator;
import cod.semantic.ReturnContractValidator;
import cod.lexer.TokenType.Keyword;
import java.util.*;

import cod.lexer.Token;
import static cod.lexer.TokenType.*;
import static cod.lexer.TokenType.Keyword.*;
import static cod.lexer.TokenType.Symbol.*;

public class DeclarationParser extends BaseParser {

  private final StatementParser statementParser;
  private final SlotParser slotParser;
  private final PolicyValidator policyValidator;

  private Type currentParsingClass = null;
  private Method currentParsingMethod = null;

  public DeclarationParser(ParserContext ctx, StatementParser statementParser, ImportResolver importResolver) {
    super(ctx);
    this.statementParser = statementParser;
    this.slotParser = new SlotParser(this);
    this.policyValidator = new PolicyValidator(importResolver);
  }

  @Override
  protected BaseParser createIsolatedParser(ParserContext isolatedCtx) {
    return new DeclarationParser(isolatedCtx, this.statementParser, this.policyValidator.getImportResolver());
  }
  
  public StatementParser getStatementParser() {
    return statementParser;
  }

  private void setCurrentParsingClass(Type type) { currentParsingClass = type; }
  private Type getCurrentParsingClass() { return currentParsingClass; }
  private void setCurrentParsingMethod(Method method) { currentParsingMethod = method; }

  @Override
  protected boolean isUnsafeTypeContext() {
    if (super.isUnsafeTypeContext()) return true;
    if (currentParsingMethod != null && currentParsingMethod.isUnsafe) return true;
    return currentParsingClass != null && currentParsingClass.isUnsafe;
  }

  public void validateClassViralPolicies(Type type, Program currentProgram) {
    policyValidator.validateClassViralPolicies(type, currentProgram);
  }

  public void validateAllPolicyMethods(Type type, Program currentProgram) {
    policyValidator.validateAllPolicyMethods(type, currentProgram);
  }
  
  // === LL(0..3) Dispatches ===

  public boolean isConstructorDeclaration() {
    if (match(THIS, LPAREN)) return true;
    if (match(VISIBILITY, THIS, LPAREN)) return true;
    return false;
  }

  public boolean isPolicyDeclaration() {
    if (match(POLICY, ID)) return true;
    if (match(VISIBILITY, POLICY, ID)) return true;
    return false;
  }

  public boolean isPolicyMethodDeclarationStart() {
    if (is(now(), BUILTIN, SHARE, LOCAL)) return false;
    Token nameToken = now();
    if (!(is(nameToken, ID) || canBeMethod(nameToken))) return false;
    return match(ANY, LPAREN);
  }

  public Constructor parseConstructor() {
    Token startToken = now();
    Token thisToken = null;

    if (is(SHARE, LOCAL)) consume();

    thisToken = now();
    Token current = consume();
    if (!is(current, THIS)) {
      throw error("Constructor must be named 'this', found: " + current.getText(), startToken);
    }

    Constructor constructor = ASTFactory.createConstructor(null, null, thisToken);
    expect(LPAREN);
    if (!is(RPAREN)) {
      constructor.parameters.add(parseParameter());
      while (consume(COMMA)) {
        constructor.parameters.add(parseParameter());
      }
    }
    expect(RPAREN);

    if (is(DOUBLE_COLON)) {
      throw error("Constructors cannot have return slots contracts (:: syntax). Remove '::' and return type declarations.");
    }
    if (is(TILDE_ARROW)) {
      throw error("Constructors cannot use inline return (~>) syntax. Use a block body: this(...) { ... }");
    }

    boolean hasSuperCall = false;
    if (looksLikeSuperConstructorCall()) {
      hasSuperCall = true;
      constructor.body.add(parseSuperConstructorCall());
    }

    if (is(LBRACE)) {
      expect(LBRACE);
      while (!is(RBRACE)) {
        constructor.body.add(statementParser.parseStmt());
      }
      expect(RBRACE);
    } else if (!hasSuperCall) {
      throw error("Constructor must have a body: this(...) { ... } or this(...) super(...) { ... }");
    }

    return constructor;
  }

  private boolean looksLikeSuperConstructorCall() {
    return match(SUPER, LPAREN);
  }

  private MethodCall parseSuperConstructorCall() {
    Token superToken = expect(SUPER);
    String zuper = SUPER.toString();
    MethodCall superCall = ASTFactory.createMethodCall(zuper, zuper, superToken);
    superCall.isSuperCall = true;
    
    expect(LPAREN);
    if (!is(RPAREN)) {
      if (isNamedArgument()) {
        parseNamedArgumentList(superCall.arguments, superCall.argNames);
      } else {
        superCall.arguments.add(statementParser.expressionParser.parseExpr());
        superCall.argNames.add(null);
        while (consume(COMMA)) {
          superCall.arguments.add(statementParser.expressionParser.parseExpr());
          superCall.argNames.add(null);
        }
      }
    }
    expect(RPAREN);
    return superCall;
  }

  private boolean isNamedArgument() {
    return match(ID, COLON);
  }

  private void parseNamedArgumentList(List<Expr> args, List<String> argNames) {
    do {
      String argName = expect(ID).getText();
      expect(COLON);
      Expr value = statementParser.expressionParser.parseExpr();
      args.add(value);
      argNames.add(argName);
      if (!is(COMMA)) break;
      expect(COMMA);
    } while (!is(RPAREN));
  }

  public Type parseType() {
    Keyword visibility = null;
    Token visibilityToken = null;
    boolean isUnsafeType = false;

    if (is(SHARE, LOCAL)) {
        visibilityToken = now();
        Token currentVisibility = consume();
        if (is(currentVisibility, SHARE)) {
            visibility = Keyword.SHARE;
        } else if (is(currentVisibility, LOCAL)) {
            visibility = Keyword.LOCAL;
        }
    }

    if (is(UNSAFE)) {
        consume();
        isUnsafeType = true;
    }

    Token typeNameToken = now();
    String typeName = expect(ID).getText();

    if (is(LPAREN)) return null;

    NamingValidator.validateClassName(typeName, typeNameToken);

    String extendName = null;
    Token extendToken = null;
    Token parentToken = null;
    
    if (is(IS)) {
        extendToken = now();
        expect(IS);
        parentToken = now();
        extendName = parseQualifiedName();
    }

    List<String> implementedPolicies = new ArrayList<String>();
    java.util.Map<String, Token> policyTokens = new java.util.HashMap<String, Token>();
    
    while (is(WITH)) {
        expect(WITH);
        Token policyToken = now();
        String policyName = parseQualifiedName();
        implementedPolicies.add(policyName);
        policyTokens.put(policyName, policyToken);

        while (consume(COMMA)) {
            policyToken = now();
            policyName = parseQualifiedName();
            implementedPolicies.add(policyName);
            policyTokens.put(policyName, policyToken);
        }
    }

    Type type = ASTFactory.createType(typeName, visibility, extendName, typeNameToken);
    type.isUnsafe = isUnsafeType;
    type.implementedPolicies = implementedPolicies;
    type.policyTokens = policyTokens;
    type.extendToken = extendToken;
    type.parentToken = parentToken;

    setCurrentParsingClass(type);

    if (type.isUnsafe) ctx.enterUnsafeDeclaration();
    try {
      expect(LBRACE);
      while (!is(RBRACE)) {
          if (isFieldDeclaration()) {
              type.fields.add(parseField());
          } else if (isConstructorDeclaration()) {
              type.constructors.add(parseConstructor());
          } else if (isMethodDeclaration()) {
              Method method = parseMethod();
              method.associatedClass = type.name;
              type.methods.add(method);
          } else {
              type.statements.add(statementParser.parseStmt());
          }
        }
      expect(RBRACE);
    } finally {
      if (type.isUnsafe) ctx.exitUnsafeDeclaration();
      setCurrentParsingClass(null);
    }
    return type;
}

  public Policy parsePolicy() {
    Keyword visibility = null;
    Token visibilityToken = null;
    if (is(SHARE, LOCAL)) {
      visibilityToken = consume();
      if (is(visibilityToken, SHARE)) visibility = Keyword.SHARE;
      else if (is(visibilityToken, LOCAL)) visibility = Keyword.LOCAL;
    }

    expect(POLICY);
    Token nameToken = now();
    String policyName = expect(ID).getText();
    NamingValidator.validatePolicyName(policyName, nameToken);

    List<String> composedPolicies = new ArrayList<String>();
    if (is(WITH)) {
      expect(WITH);
      composedPolicies.add(parseQualifiedName());
      while (consume(COMMA)) composedPolicies.add(parseQualifiedName());
    }

    Policy policy = ASTFactory.createPolicy(policyName, visibility, nameToken);
    policy.composedPolicies = composedPolicies;

    expect(LBRACE);
    while (!is(RBRACE)) {
      if (isPolicyMethodDeclarationStart()) {
        policy.methods.add(parsePolicyMethod());
      } else if (!is(RBRACE)) {
        throw error("Policy can only contain method declarations and cannot have a body.", now());
      }
    }
    expect(RBRACE);

    policyValidator.registerLocalPolicy(policy);
    policyValidator.validatePolicyComposition(policy, nameToken);
    return policy;
  }

  public PolicyMethod parsePolicyMethod() {
    Token methodNameToken = now();
    String methodName;

    if (canBeMethod(now())) methodName = consume().getText();
    else if (is(ID)) methodName = expect(ID).getText();
    else throw error("Expected method name in policy declaration");

    NamingValidator.validatePolicyMethodName(methodName, methodNameToken);
    PolicyMethod method = ASTFactory.createPolicyMethod(methodName, methodNameToken);
    
    expect(LPAREN);
    if (!is(RPAREN)) {
      method.parameters.add(parseParameter());
      while (consume(COMMA)) method.parameters.add(parseParameter());
    }
    expect(RPAREN);

    if (is(DOUBLE_COLON)) {
      method.returnSlots = slotParser.parseSlotContract();
    }
    return method;
  }

  public Method parseMethod() {
    Token startToken = now();

    boolean isBuiltin = false;
    Keyword visibility = Keyword.SHARE;
    boolean isPolicyMethod = false;
    boolean isUnsafeMethod = false;
    Token visibilityToken = null;
    boolean sawVisibility = false;

    if (is(SHARE, LOCAL)) {
      sawVisibility = true;
      visibilityToken = now();
      Token currentVisibility = consume();
      if (is(currentVisibility, SHARE)) visibility = Keyword.SHARE;
      else if (is(currentVisibility, LOCAL)) visibility = Keyword.LOCAL;
    }

    boolean consumedModifier = true;
    while (consumedModifier) {
      consumedModifier = false;
      if (is(POLICY)) { expect(POLICY); isPolicyMethod = true; consumedModifier = true; continue; }
      if (is(BUILTIN)) { expect(BUILTIN); isBuiltin = true; consumedModifier = true; continue; }
      if (is(UNSAFE)) { expect(UNSAFE); isUnsafeMethod = true; consumedModifier = true; }
    }

    if (is(SHARE, LOCAL)) throw error("Visibility modifier must appear before other modifiers in method declarations", now());

    if (isUnsafeMethod && !sawVisibility) {
      throw error("Unsafe methods require an explicit visibility modifier before 'unsafe'.", startToken);
    }

    if (isPolicyMethod && !sawVisibility) {
      Type currentClass = getCurrentParsingClass();
      if (!nil(currentClass)) visibility = currentClass.visibility;
      else visibility = Keyword.SHARE;
    }

    String methodName;
    Token nameToken = now();
    if (canBeMethod(now())) {
        methodName = now().getText();
        consume();
    } else if (is(ID)) {
        methodName = expect(ID).getText();
    } else {
        throw error("Expected method name (identifier or allowed keyword)");
    }

    NamingValidator.validateMethodName(methodName, startToken);

    Method method = ASTFactory.createMethod(methodName, visibility, null, nameToken);
    method.isBuiltin = isBuiltin;
    method.isPolicyMethod = isPolicyMethod;
    method.isUnsafe = isUnsafeMethod;

    if (method.isUnsafe) ctx.enterUnsafeDeclaration();
    setCurrentParsingMethod(method);

    try {
      expect(LPAREN);

      if (isBuiltin) {
          int parenDepth = 1;
          while (!is(EOF) && parenDepth > 0) {
              Token t = now();
              if (is(t, LPAREN)) parenDepth++;
              else if (is(t, RPAREN)) {
                  parenDepth--;
                  if (parenDepth == 0) {
                      expect(RPAREN);
                      break;
                  }
              }
              consume();
          }
      } else {
          if (!is(RPAREN)) {
              method.parameters.add(parseParameter());
              while (consume(COMMA)) method.parameters.add(parseParameter());
          }
          expect(RPAREN);
      }

      if (is(DOUBLE_COLON)) {
          method.returnSlots = slotParser.parseSlotContract();
      } else {
          method.returnSlots = new ArrayList<Slot>();
      }

      if (isBuiltin) {
          while (getPosition() < tokens.size()) {
              Token current = now();
              if (is(current, RBRACE) || is(current, SHARE, LOCAL, BUILTIN, POLICY, UNSAFE)) break;
              consume();
          }

          if (is(TILDE_ARROW, LBRACE)) {
              throw error("Builtin method '" + methodName + "' cannot have a body.", now());
          }
          return method;
      }

      if (is(TILDE_ARROW)) {
          Token tildeArrowToken = expect(TILDE_ARROW);
          List<SlotAssignment> slotAssignments = slotParser.parseParenthesizedSlotAssignments(tildeArrowToken);

          if (slotAssignments.size() == 1) {
              method.body.add(slotAssignments.get(0));
          } else {
              method.body.add(ASTFactory.createMultipleSlotAsmt(slotAssignments, tildeArrowToken));
          }
      } else if (is(LBRACE)) {
          expect(LBRACE);
          while (!is(RBRACE)) {
              method.body.add(statementParser.parseStmt());
          }
          expect(RBRACE);
          ReturnContractValidator.validateMethodReturnContract(method, currentParsingClass, startToken);
      } else {
          throw error("Expected '~>' or '{' after method signature.", now());
      }
    } finally {
      setCurrentParsingMethod(null);
      if (method.isUnsafe) ctx.exitUnsafeDeclaration();
    }
    return method;
}

  public List<Slot> parseSlotContractList() {
    return slotParser.parseSlotContract();
  }

  public Field parseField() {
    Token startToken = now();
    Keyword visibility = null;
    
    if (is(SHARE, LOCAL)) {
      Token visibilityToken = consume();
      if (is(visibilityToken, SHARE)) visibility = SHARE;
      else if (is(visibilityToken, LOCAL)) visibility = LOCAL;
    }

    Token fieldNameToken = now();
    String fieldName = expect(ID).getText();

    expect(COLON);
    String fieldType = parseTypeReference();

    NamingValidator.validateFieldName(fieldName, startToken);

    Field field = ASTFactory.createField(fieldName, fieldType, fieldNameToken);
    if (visibility != null) field.visibility = visibility;

    if (consume(ASSIGN)) {
      field.value = statementParser.expressionParser.parseExpr();
    }

    if (NamingValidator.isAllCaps(fieldName) && field.value == null) {
      throw error("Constant field '" + fieldName + "' must have an initial value", fieldNameToken);
    }
    return field;
  }

  public Param parseParameter() {
    Token startToken = now();
    
    if (!is(ID)) {
      throw error("Expected parameter name (identifier), but found " + 
                  getTypeName(startToken.type) + " ('" + startToken.getText() + "'). " +
                  "If this is a script, ensure it is a valid top-level statement.");
    }
    
    String name = expect(ID).getText();

    if (consume(DOUBLE_COLON_ASSIGN)) {
      Expr defaultValue = statementParser.expressionParser.parsePrimaryExpr();
      if (!isSimpleLiteral(defaultValue)) {
        throw error("Parameter inference (:=) can only be used with literals. Use explicit typing for expressions.", startToken);
      }

      String inferredType = inferTypeFromLiteral(defaultValue);
      if (inferredType == null) {
        throw error("Cannot infer parameter type from literal. Use explicit typing.", startToken);
      }

      NamingValidator.validateParameterName(name, startToken);
      Param param = ASTFactory.createParam(name, inferredType, defaultValue, true, startToken);
      param.hasDefaultValue = true;
      return param;
    }

    expect(COLON);
    String type = parseTypeReference();

    Expr defaultValue = null;
    if (consume(ASSIGN)) {
      defaultValue = statementParser.expressionParser.parseExpr();
    }

    NamingValidator.validateParameterName(name, startToken);
    Param param = ASTFactory.createParam(name, type, defaultValue, false, startToken);
    if (defaultValue != null) param.hasDefaultValue = true;
    return param;
  }

  private boolean isSimpleLiteral(Expr expr) {
    if (expr == null) return false;
    if (expr instanceof IntLiteral || expr instanceof FloatLiteral || expr instanceof BoolLiteral || expr instanceof NoneLiteral || expr instanceof TextLiteral) return true;
    if (expr instanceof Array) {
      Array arr = (Array) expr;
      if (arr.elements.size() == 1 && arr.elements.get(0) instanceof Range) {
        Range range = (Range) arr.elements.get(0);
        return isSimpleLiteral(range.start) && isSimpleLiteral(range.end) && (nil(range.step) || isSimpleLiteral(range.step));
      }
      for (Expr elem : arr.elements) if (!isSimpleLiteral(elem)) return false;
      return true;
    }
    if (expr instanceof Range) {
      Range range = (Range) expr;
      return isSimpleLiteral(range.start) && isSimpleLiteral(range.end) && (nil(range.step) || isSimpleLiteral(range.step));
    }
    if (expr instanceof Tuple) {
      Tuple tuple = (Tuple) expr;
      for (Expr elem : tuple.elements) if (!isSimpleLiteral(elem)) return false;
      return true;
    }
    return false;
  }

  private String inferTypeFromLiteral(Expr expr) {
    if (nil(expr)) return null;
    if (expr instanceof IntLiteral) return INT.toString();
    if (expr instanceof FloatLiteral) return FLOAT.toString();
    if (expr instanceof BoolLiteral) return BOOL.toString();
    if (expr instanceof TextLiteral) return TEXT.toString();
    if (expr instanceof NoneLiteral) return null;

    if (expr instanceof Array) {
      Array arr = (Array) expr;
      if (arr.elements.isEmpty()) return null;
      if (arr.elements.size() == 1 && arr.elements.get(0) instanceof Range) return "[]";
      String elementType = inferTypeFromLiteral(arr.elements.get(0));
      if (elementType != null) return "[" + elementType + "]";
      return null;
    }
    if (expr instanceof Range) return "[]";
    if (expr instanceof Tuple) {
      Tuple tuple = (Tuple) expr;
      if (tuple.elements.isEmpty()) return null;
      StringBuilder sb = new StringBuilder("(");
      for (int i = 0; i < tuple.elements.size(); i++) {
        String elemType = inferTypeFromLiteral(tuple.elements.get(i));
        if (elemType == null) return null;
        if (i > 0) sb.append(",");
        sb.append(elemType);
      }
      sb.append(")");
      return sb.toString();
    }
    return null;
  }

  private boolean isMethodDeclaration() {
    int offset = 0;
    if (is(next(offset), SHARE, LOCAL, BUILTIN, POLICY, UNSAFE)) {
        offset++;
        while (is(next(offset), BUILTIN, POLICY, UNSAFE)) offset++;
    }
    Token nameToken = next(offset);
    if (!(is(nameToken, ID) || canBeMethod(nameToken))) return false;
    offset++;
    return is(next(offset), LPAREN);
  }

  private boolean isFieldDeclaration() {
    int offset = 0;
    if (is(next(offset), SHARE, LOCAL)) offset++;
    if (!is(next(offset), ID)) return false;
    offset++;
    return is(next(offset), COLON);
  }
}