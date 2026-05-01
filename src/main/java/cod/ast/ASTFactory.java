package cod.ast;

import cod.ast.node.*;
import cod.lexer.TokenType.Keyword;
import cod.lexer.Token;
import java.util.*;
import cod.math.AutoStackingNumber;

public class ASTFactory {
  // Helper method to create source span from token
  private static SourceSpan span(Token token) {
    return token != null ? new SourceSpan(token) : null;
  }

  // Merge source spans for composite nodes
  private static SourceSpan mergeSpans(SourceSpan... spans) {
    if (spans == null || spans.length == 0) return null;

    SourceSpan result = spans[0];
    for (int i = 1; i < spans.length; i++) {
      if (spans[i] != null) {
        result = SourceSpan.merge(result, spans[i]);
      }
    }
    return result;
  }

  public static Program createProgram() {
    return new Program();
  }

  public static ChainedComparison createChainedComparison(
      List<Expr> expressions, List<String> operators, Token firstToken) {
    ChainedComparison node = new ChainedComparison(expressions, operators);

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (firstToken != null) spans.add(span(firstToken));
    for (Expr expr : expressions) {
      if (expr != null) spans.add(expr.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static Lambda createLambda(
      List<Param> parameters, List<Slot> returnSlots, Stmt body, Token lambdaToken) {
    Lambda lambda = new Lambda(parameters, returnSlots, body);
    if (lambdaToken != null) {
      lambda.setSourceSpan(span(lambdaToken));
    }
    return lambda;
  }

  public static Lambda createLambda(List<Param> parameters, Token lambdaToken) {
    Lambda lambda = new Lambda();
    lambda.parameters = parameters;
    if (lambdaToken != null) {
      lambda.setSourceSpan(span(lambdaToken));
    }
    return lambda;
  }

  public static Unit createUnit(String name, Token unitToken) {
    Unit unit = new Unit();
    unit.name = name;
    unit.imports = new Use();
    unit.types = new ArrayList<Type>();
    unit.resolvedImports = new HashMap<String, Program>();
    unit.mainClassName = null;
    if (unitToken != null) {
      unit.setSourceSpan(span(unitToken));
    }
    return unit;
  }

  public static Unit createUnit(String name, String mainClassName, Token unitToken) {
    Unit unit = new Unit();
    unit.name = name;
    unit.imports = new Use();
    unit.types = new ArrayList<Type>();
    unit.resolvedImports = new HashMap<String, Program>();
    unit.mainClassName = mainClassName;
    if (unitToken != null) {
      unit.setSourceSpan(span(unitToken));
    }
    return unit;
  }

  public static Use createUseNode(List<String> imports, Token useToken) {
    Use useNode = new Use();
    useNode.imports = imports;
    if (useToken != null) {
      useNode.setSourceSpan(span(useToken));
    }
    return useNode;
  }

  public static Type createType(
      String name, Keyword visibility, String extendName, Token nameToken) {
    Type type = new Type();
    type.name = name;
    type.visibility = visibility;
    type.extendName = extendName;
    type.fields = new ArrayList<Field>();
    type.methods = new ArrayList<Method>();
    type.statements = new ArrayList<Stmt>();
    type.constructors = new ArrayList<Constructor>();
    type.implementedPolicies = new ArrayList<String>();
    if (nameToken != null) {
      type.setSourceSpan(span(nameToken));
    }
    return type;
  }

  public static Policy createPolicy(String name, Keyword visibility, Token nameToken) {
    Policy node = new Policy();
    node.name = name;
    node.visibility = visibility;
    node.methods = new ArrayList<PolicyMethod>();
    node.composedPolicies = new ArrayList<String>();
    if (nameToken != null) {
      node.setSourceSpan(span(nameToken));
    }
    return node;
  }

  public static PolicyMethod createPolicyMethod(String name, Token nameToken) {
    PolicyMethod method = new PolicyMethod();
    method.methodName = name;
    method.parameters = new ArrayList<Param>();
    method.returnSlots = new ArrayList<Slot>();
    if (nameToken != null) {
      method.setSourceSpan(span(nameToken));
    }
    return method;
  }

  public static Field createField(String name, String type, Expr value, Token nameToken) {
    Field field = new Field();
    field.name = name;
    field.type = type;
    field.value = value;
    if (nameToken != null) {
      field.setSourceSpan(span(nameToken));
    }
    return field;
  }

  public static Field createField(String name, String type, Token nameToken) {
    return createField(name, type, null, nameToken);
  }

  public static Assignment createAsmt(
      Expr target, Expr value, boolean isDeclaration, Token assignToken) {
    Assignment assignment = new Assignment();
    assignment.left = target;
    assignment.right = value;
    assignment.isDeclaration = isDeclaration;
    assignment.setSourceSpan(
        mergeSpans(
            target != null ? target.getSourceSpan() : null,
            span(assignToken),
            value != null ? value.getSourceSpan() : null));
    return assignment;
  }

  public static ConstructorCall createConstructorCall(
      String className, List<Expr> arguments, Token nameToken) {
    ConstructorCall call = new ConstructorCall();
    call.className = className;
    call.arguments = arguments != null ? arguments : new ArrayList<Expr>();
    call.argNames = new ArrayList<String>();
    if (arguments != null) {
      for (int i = 0; i < arguments.size(); i++) {
        call.argNames.add(null);
      }
    }
    if (nameToken != null) {
      call.setSourceSpan(span(nameToken));
    }
    return call;
  }

  public static Constructor createConstructor(
      List<Param> parameters, List<Stmt> body, Token thisToken) {
    Constructor cons = new Constructor();
    cons.parameters = parameters != null ? parameters : new ArrayList<Param>();
    cons.body = body != null ? body : new ArrayList<Stmt>();
    if (thisToken != null) {
      cons.setSourceSpan(span(thisToken));
    }
    return cons;
  }

  public static Method createMethod(
      String name, Keyword visibility, List<Slot> returnSlots, Token nameToken) {
    Method node = new Method();
    node.methodName = name;
    node.visibility = visibility;
    node.returnSlots = returnSlots != null ? returnSlots : new ArrayList<Slot>();
    node.parameters = new ArrayList<Param>();
    node.body = new ArrayList<Stmt>();
    node.isBuiltin = false;
    if (nameToken != null) {
      node.setSourceSpan(span(nameToken));
    }
    return node;
  }

  public static Param createParam(
      String name, String type, Expr defaultValue, boolean typeInferred, Token nameToken) {
    Param param = new Param();
    param.name = name;
    param.type = type;
    if (defaultValue != null) {
      param.defaultValue = defaultValue;
      param.hasDefaultValue = true;
    }
    param.typeInferred = typeInferred;
    if (nameToken != null) {
      param.setSourceSpan(span(nameToken));
    }
    return param;
  }

  public static Slot createSlot(String type, String name, Token nameToken) {
    Slot slot = new Slot();
    slot.type = type;
    slot.name = name;
    if (nameToken != null) {
      slot.setSourceSpan(span(nameToken));
    }
    return slot;
  }

  public static PropertyAccess createPropertyAccess(
      Expr left, Expr right, Token dotToken) {
    PropertyAccess node = new PropertyAccess();
    node.left = left;
    node.right = right;
    node.dotToken = dotToken;
    node.setSourceSpan(
        mergeSpans(
            left != null ? left.getSourceSpan() : null,
            span(dotToken),
            right != null ? right.getSourceSpan() : null));
    return node;
  }

  public static Expr createIdentifier(String name, Token token) {
    Identifier node = new Identifier(name);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createIntLiteral(int value, Token token) {
    IntLiteral node = new IntLiteral(value);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createLongLiteral(long value, Token token) {
    IntLiteral node = new IntLiteral(value);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createFloatLiteral(AutoStackingNumber value, Token token) {
    FloatLiteral node = new FloatLiteral(value);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createTextLiteral(String value, Token token) {
    TextLiteral node = new TextLiteral(value);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createBoolLiteral(boolean value, Token token) {
    BoolLiteral node = new BoolLiteral(value);
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createNoneLiteral(Token token) {
    NoneLiteral node = new NoneLiteral();
    if (token != null) {
      node.setSourceSpan(span(token));
    }
    return node;
  }

  public static Expr createThisExpr(String className, Token thisToken) {
    This node = (className != null) ? new This(className) : new This();
    if (thisToken != null) {
      node.setSourceSpan(span(thisToken));
    }
    return node;
  }

  public static Expr createSuperExpr(Token superToken) {
    Super node = new Super();
    if (superToken != null) {
      node.setSourceSpan(span(superToken));
    }
    return node;
  }

  public static BinaryOp createBinaryOp(
      Expr left, String op, Expr right, Token opToken) {
    BinaryOp node = new BinaryOp();
    node.left = left;
    node.op = op;
    node.right = right;
    node.setSourceSpan(
        mergeSpans(
            left != null ? left.getSourceSpan() : null,
            span(opToken),
            right != null ? right.getSourceSpan() : null));
    return node;
  }

  public static EqualityChain createEqualityChain(
      Expr left,
      String operator,
      boolean isAllChain,
      List<Expr> chainArguments,
      Token leftToken,
      Token opToken,
      Token chainToken) {
    EqualityChain chain = new EqualityChain();
    chain.left = left;
    chain.operator = operator;
    chain.isAllChain = isAllChain;
    chain.chainArguments = chainArguments != null ? chainArguments : new ArrayList<Expr>();

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (left != null) spans.add(left.getSourceSpan());
    if (leftToken != null) spans.add(span(leftToken));
    if (opToken != null) spans.add(span(opToken));
    if (chainToken != null) spans.add(span(chainToken));
    for (Expr arg : chainArguments) {
      if (arg != null) spans.add(arg.getSourceSpan());
    }

    chain.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return chain;
  }

  public static BooleanChain createBooleanChain(
      boolean isAll, List<Expr> expressions, Token keywordToken) {
    BooleanChain node = new BooleanChain();
    node.isAll = isAll;
    node.expressions = expressions != null ? expressions : new ArrayList<Expr>();

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (keywordToken != null) spans.add(span(keywordToken));
    for (Expr expr : node.expressions) {
      if (expr != null) spans.add(expr.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static Unary createUnaryOp(String op, Expr operand, Token opToken) {
    Unary node = new Unary();
    node.op = op;
    node.operand = operand;
    node.setSourceSpan(mergeSpans(span(opToken), operand != null ? operand.getSourceSpan() : null));
    return node;
  }

  public static TypeCast createTypeCast(
      String targetType, Expr expression, Token lparenToken) {
    TypeCast node = new TypeCast();
    node.targetType = targetType;
    node.expression = expression;
    node.setSourceSpan(
        mergeSpans(span(lparenToken), expression != null ? expression.getSourceSpan() : null));
    return node;
  }

  public static SlotAssignment createImplicitReturn(Expr returnExpr, Token returnToken) {
    SlotAssignment returnStmt = new SlotAssignment();
    returnStmt.slotName = "_";
    returnStmt.value = returnExpr;
    returnStmt.setSourceSpan(
        mergeSpans(
            returnToken != null ? span(returnToken) : null,
            returnExpr != null ? returnExpr.getSourceSpan() : null));
    return returnStmt;
  }

  public static MethodCall createMethodCall(
      String name, String qualifiedName, Token nameToken) {
    MethodCall call = new MethodCall();
    call.name = name;
    call.qualifiedName = qualifiedName;
    call.arguments = new ArrayList<Expr>();
    call.slotNames = new ArrayList<String>();
    call.argNames = new ArrayList<String>();
    call.isSuperCall = false;
    call.isGlobal = false;
    if (nameToken != null) {
      call.setSourceSpan(span(nameToken));
    }
    return call;
  }

  public static Array createArray(List<Expr> elements, Token lbracketToken) {
    Array array = new Array();
    array.elements = elements != null ? elements : new ArrayList<Expr>();

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (lbracketToken != null) spans.add(span(lbracketToken));
    for (Expr elem : array.elements) {
      if (elem != null) spans.add(elem.getSourceSpan());
    }

    array.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return array;
  }

  public static Tuple createTuple(List<Expr> elements, Token lparenToken) {
    Tuple node = new Tuple();
    node.elements = elements != null ? elements : new ArrayList<Expr>();

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (lparenToken != null) spans.add(span(lparenToken));
    for (Expr elem : node.elements) {
      if (elem != null) spans.add(elem.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static IndexAccess createIndexAccess(
      Expr array, Expr index, Token lbracketToken) {
    IndexAccess node = new IndexAccess();
    node.array = array;
    node.index = index;
    node.setSourceSpan(
        mergeSpans(
            array != null ? array.getSourceSpan() : null,
            span(lbracketToken),
            index != null ? index.getSourceSpan() : null));
    return node;
  }

  public static RangeIndex createRangeIndex(
      Expr step, Expr start, Expr end, Token byToken, Token toToken) {
    RangeIndex node = new RangeIndex(step, start, end);

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (byToken != null) spans.add(span(byToken));
    if (start != null) spans.add(start.getSourceSpan());
    if (toToken != null) spans.add(span(toToken));
    if (end != null) spans.add(end.getSourceSpan());
    if (step != null) spans.add(step.getSourceSpan());

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static MultiRangeIndex createMultiRangeIndex(
      List<RangeIndex> ranges, Token firstLbracketToken) {
    MultiRangeIndex node = new MultiRangeIndex(ranges);

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (firstLbracketToken != null) spans.add(span(firstLbracketToken));
    for (RangeIndex range : ranges) {
      if (range != null) spans.add(range.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static ExprIf createIfExpr(
      Expr condition, Expr thenExpr, Expr elseExpr, Token ifToken, Token elseToken) {
    ExprIf node = new ExprIf();
    node.condition = condition;
    node.thenExpr = thenExpr;
    node.elseExpr = elseExpr;
    node.setSourceSpan(
        mergeSpans(
            span(ifToken),
            condition != null ? condition.getSourceSpan() : null,
            thenExpr != null ? thenExpr.getSourceSpan() : null,
            span(elseToken),
            elseExpr != null ? elseExpr.getSourceSpan() : null));
    return node;
  }

  public static StmtIf createIfStmt(Expr condition, Token ifToken) {
    StmtIf stmtIfNode = new StmtIf();
    stmtIfNode.condition = condition;
    stmtIfNode.thenBlock = new Block();
    stmtIfNode.elseBlock = new Block();
    if (ifToken != null) {
      stmtIfNode.setSourceSpan(span(ifToken));
    }
    return stmtIfNode;
  }

  public static For createFor(
      String iterator, Range range, Token forToken, Token iteratorToken) {
    For forNode = new For();
    forNode.iterator = iterator;
    forNode.range = range;
    forNode.arraySource = null;
    forNode.body = new Block();
    forNode.setSourceSpan(
        mergeSpans(
            span(forToken), span(iteratorToken), range != null ? range.getSourceSpan() : null));
    return forNode;
  }

  public static For createFor(
      String iterator, Expr arraySource, Token forToken, Token iteratorToken) {
    For forNode = new For();
    forNode.iterator = iterator;
    forNode.range = null;
    forNode.arraySource = arraySource;
    forNode.body = new Block();
    forNode.setSourceSpan(
        mergeSpans(
            span(forToken),
            span(iteratorToken),
            arraySource != null ? arraySource.getSourceSpan() : null));
    return forNode;
  }

  public static Range createRange(
      Expr step, Expr start, Expr end, Token byToken, Token toToken) {
    Range node = new Range(step, start, end);

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (byToken != null) spans.add(span(byToken));
    if (start != null) spans.add(start.getSourceSpan());
    if (toToken != null) spans.add(span(toToken));
    if (end != null) spans.add(end.getSourceSpan());
    if (step != null) spans.add(step.getSourceSpan());

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static Block createBlock(Token lbraceToken) {
    Block block = new Block();
    if (lbraceToken != null) {
      block.setSourceSpan(span(lbraceToken));
    }
    return block;
  }

  public static Block createBlock(
      List<Stmt> statements, Token lbraceToken, Token rbraceToken) {
    Block block = new Block(statements);
    if (lbraceToken != null && rbraceToken != null) {
      block.setSourceSpan(new SourceSpan(lbraceToken, rbraceToken));
    } else if (lbraceToken != null) {
      block.setSourceSpan(span(lbraceToken));
    }
    return block;
  }

  public static Var createVar(String name, Expr value, Token nameToken) {
    Var var = new Var();
    var.name = name;
    var.value = value;
    if (nameToken != null) {
      var.setSourceSpan(span(nameToken));
    }
    return var;
  }

  public static Exit createExit(Token exitToken) {
    Exit exit = new Exit();
    if (exitToken != null) {
      exit.setSourceSpan(span(exitToken));
    }
    return exit;
  }

  public static ArgumentList createArgumentList(List<Expr> arguments, Token lparenToken) {
    ArgumentList node = new ArgumentList();
    node.arguments = arguments != null ? arguments : new ArrayList<Expr>();

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (lparenToken != null) spans.add(span(lparenToken));
    for (Expr arg : node.arguments) {
      if (arg != null) spans.add(arg.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static Skip createSkipStmt(Token skipToken) {
    Skip skip = new Skip();
    if (skipToken != null) {
      skip.setSourceSpan(span(skipToken));
    }
    return skip;
  }

  public static Break createBreakStmt(Token breakToken) {
    Break brk = new Break();
    if (breakToken != null) {
      brk.setSourceSpan(span(breakToken));
    }
    return brk;
  }

  public static ReturnSlotAssignment createReturnSlotAsmt(
      List<String> variableNames, Lambda lambda, Token assignToken) {
    ReturnSlotAssignment assignment = new ReturnSlotAssignment();
    assignment.variableNames = variableNames;
    assignment.lambda = lambda;
    assignment.setSourceSpan(
        mergeSpans(span(assignToken), lambda != null ? lambda.getSourceSpan() : null));
    return assignment;
  }

  public static ReturnSlotAssignment createReturnSlotAsmt(
      List<String> variableNames, MethodCall methodCall, Token assignToken) {
    ReturnSlotAssignment assignment = new ReturnSlotAssignment();
    assignment.variableNames = variableNames;
    assignment.methodCall = methodCall;
    assignment.setSourceSpan(
        mergeSpans(span(assignToken), methodCall != null ? methodCall.getSourceSpan() : null));
    return assignment;
  }

  public static SlotDeclaration createSlotDeclaration(
      List<String> slotNames, Token lbracketToken) {
    SlotDeclaration node = new SlotDeclaration();
    node.slotNames = slotNames;
    if (lbracketToken != null) {
      node.setSourceSpan(span(lbracketToken));
    }
    return node;
  }

  public static SlotAssignment createSlotAsmt(
      String slotName, Expr value, Token colonToken) {
    SlotAssignment node = new SlotAssignment();
    node.slotName = slotName;
    node.value = value;
    node.setSourceSpan(
        mergeSpans(
            colonToken != null ? span(colonToken) : null,
            value != null ? value.getSourceSpan() : null));
    return node;
  }

  public static MultipleSlotAssignment createMultipleSlotAsmt(
      List<SlotAssignment> assignments, Token tildeArrowToken) {
    MultipleSlotAssignment node = new MultipleSlotAssignment();
    node.assignments = assignments;

    List<SourceSpan> spans = new ArrayList<SourceSpan>();
    if (tildeArrowToken != null) spans.add(span(tildeArrowToken));
    for (SlotAssignment assignment : assignments) {
      if (assignment != null) spans.add(assignment.getSourceSpan());
    }

    node.setSourceSpan(mergeSpans(spans.toArray(new SourceSpan[0])));
    return node;
  }

  public static String getConstructorSignature(Constructor constructor) {
    if (constructor == null || constructor.parameters == null) {
      return "()";
    }

    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < constructor.parameters.size(); i++) {
      if (i > 0) sb.append(", ");
      Param p = constructor.parameters.get(i);
      sb.append(p.name).append(": ").append(p.type);
      if (p.hasDefaultValue) {
        sb.append(" = default");
      }
    }
    sb.append(")");
    return sb.toString();
  }
}
