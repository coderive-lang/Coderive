package cod.ir;

import cod.ast.node.*;
import cod.math.AutoStackingNumber;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class DeserializationVisitor {
    private DeserializationVisitor() {}

    static Base readNode(String nodeName, Map<String, Object> values) throws IOException {
        Base node = instantiateNode(nodeName, values);
        applyNodeFields(node, values);
        return node;
    }

    private static Base instantiateNode(String nodeName, Map<String, Object> values) throws IOException {
        if ("Identifier".equals(nodeName)) {
            return new Identifier((String) values.get("name"));
        }
        if ("TextLiteral".equals(nodeName)) {
            String value = (String) values.get("value");
            boolean interpolated = asBoolean(values.get("isInterpolated"));
            return new TextLiteral(value, interpolated);
        }
        if ("BoolLiteral".equals(nodeName)) {
            return new BoolLiteral(asBoolean(values.get("value")));
        }
        if ("IntLiteral".equals(nodeName)) {
            return new IntLiteral((AutoStackingNumber) values.get("value"));
        }
        if ("FloatLiteral".equals(nodeName)) {
            return new FloatLiteral((AutoStackingNumber) values.get("value"));
        }
        if ("RangeIndex".equals(nodeName)) {
            return new RangeIndex((Expr) values.get("step"), (Expr) values.get("start"), (Expr) values.get("end"));
        }
        if ("MultiRangeIndex".equals(nodeName)) {
            return new MultiRangeIndex(DeserializationVisitor.<RangeIndex>castList(values.get("ranges")));
        }
        if ("ChainedComparison".equals(nodeName)) {
            return new ChainedComparison(
                    DeserializationVisitor.<Expr>castList(values.get("expressions")),
                    DeserializationVisitor.<String>castList(values.get("operators"))
            );
        }
        if ("ValueExpr".equals(nodeName)) {
            return new ValueExpr(values.get("value"));
        }
        if ("This".equals(nodeName)) {
            String className = (String) values.get("className");
            return className == null ? new This() : new This(className);
        }

        if ("Program".equals(nodeName)) return new Program();
        if ("Unit".equals(nodeName)) return new Unit();
        if ("Use".equals(nodeName)) return new Use();
        if ("Type".equals(nodeName)) return new Type();
        if ("Field".equals(nodeName)) return new Field();
        if ("Method".equals(nodeName)) return new Method();
        if ("Param".equals(nodeName)) return new Param();
        if ("Constructor".equals(nodeName)) return new Constructor();
        if ("ConstructorCall".equals(nodeName)) return new ConstructorCall();
        if ("Policy".equals(nodeName)) return new Policy();
        if ("PolicyMethod".equals(nodeName)) return new PolicyMethod();
        if ("Block".equals(nodeName)) return new Block();
        if ("Assignment".equals(nodeName)) return new Assignment();
        if ("Var".equals(nodeName)) return new Var();
        if ("StmtIf".equals(nodeName)) return new StmtIf();
        if ("ExprIf".equals(nodeName)) return new ExprIf();
        if ("For".equals(nodeName)) return new For();
        if ("Skip".equals(nodeName)) return new Skip();
        if ("Break".equals(nodeName)) return new Break();
        if ("Range".equals(nodeName)) return new Range();
        if ("Exit".equals(nodeName)) return new Exit();
        if ("Tuple".equals(nodeName)) return new Tuple();
        if ("ReturnSlotAssignment".equals(nodeName)) return new ReturnSlotAssignment();
        if ("SlotDeclaration".equals(nodeName)) return new SlotDeclaration();
        if ("SlotAssignment".equals(nodeName)) return new SlotAssignment();
        if ("MultipleSlotAssignment".equals(nodeName)) return new MultipleSlotAssignment();
        if ("BinaryOp".equals(nodeName)) return new BinaryOp();
        if ("Unary".equals(nodeName)) return new Unary();
        if ("TypeCast".equals(nodeName)) return new TypeCast();
        if ("MethodCall".equals(nodeName)) return new MethodCall();
        if ("Array".equals(nodeName)) return new Array();
        if ("IndexAccess".equals(nodeName)) return new IndexAccess();
        if ("EqualityChain".equals(nodeName)) return new EqualityChain();
        if ("BooleanChain".equals(nodeName)) return new BooleanChain();
        if ("Slot".equals(nodeName)) return new Slot();
        if ("Lambda".equals(nodeName)) return new Lambda();
        if ("NoneLiteral".equals(nodeName)) return new NoneLiteral();
        if ("Super".equals(nodeName)) return new Super();
        if ("PropertyAccess".equals(nodeName)) return new PropertyAccess();
        if ("ArgumentList".equals(nodeName)) return new ArgumentList();

        throw new IOException("Unknown IR node class: cod.ast.node." + nodeName);
    }

    private static void applyNodeFields(Base node, Map<String, Object> values) {
        if (node instanceof Program) {
            Program n = (Program) node;
            if (values.containsKey("unit")) n.unit = (Unit) values.get("unit");
            if (values.containsKey("programType")) n.programType = (cod.parser.MainParser.ProgramType) values.get("programType");
            return;
        }

        if (node instanceof Unit) {
            Unit n = (Unit) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("imports")) n.imports = (Use) values.get("imports");
            if (values.containsKey("policies")) n.policies = castList(values.get("policies"));
            if (values.containsKey("types")) n.types = castList(values.get("types"));
            if (values.containsKey("mainClassName")) n.mainClassName = (String) values.get("mainClassName");
            if (values.containsKey("resolvedImports")) n.resolvedImports = castMap(values.get("resolvedImports"));
            return;
        }

        if (node instanceof Use) {
            Use n = (Use) node;
            if (values.containsKey("imports")) n.imports = castList(values.get("imports"));
            return;
        }

        if (node instanceof Type) {
            Type n = (Type) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("visibility")) n.visibility = (cod.lexer.TokenType.Keyword) values.get("visibility");
            if (values.containsKey("extendName")) n.extendName = (String) values.get("extendName");
            if (values.containsKey("fields")) n.fields = castList(values.get("fields"));
            if (values.containsKey("constructor")) n.constructor = (Constructor) values.get("constructor");
            if (values.containsKey("methods")) n.methods = castList(values.get("methods"));
            if (values.containsKey("statements")) n.statements = castList(values.get("statements"));
            if (values.containsKey("constructors")) n.constructors = castList(values.get("constructors"));
            if (values.containsKey("implementedPolicies")) n.implementedPolicies = castList(values.get("implementedPolicies"));
            if (values.containsKey("cachedAncestorPolicies")) n.cachedAncestorPolicies = castList(values.get("cachedAncestorPolicies"));
            if (values.containsKey("viralPoliciesValidated")) n.viralPoliciesValidated = asBoolean(values.get("viralPoliciesValidated"));
            return;
        }

        if (node instanceof Field) {
            Field n = (Field) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("type")) n.type = (String) values.get("type");
            if (values.containsKey("visibility")) n.visibility = (cod.lexer.TokenType.Keyword) values.get("visibility");
            if (values.containsKey("value")) n.value = (Expr) values.get("value");
            return;
        }

        if (node instanceof Method) {
            Method n = (Method) node;
            if (values.containsKey("methodName")) n.methodName = (String) values.get("methodName");
            if (values.containsKey("associatedClass")) n.associatedClass = (String) values.get("associatedClass");
            if (values.containsKey("visibility")) n.visibility = (cod.lexer.TokenType.Keyword) values.get("visibility");
            if (values.containsKey("returnSlots")) n.returnSlots = castList(values.get("returnSlots"));
            if (values.containsKey("parameters")) n.parameters = castList(values.get("parameters"));
            if (values.containsKey("body")) n.body = castList(values.get("body"));
            if (values.containsKey("isBuiltin")) n.isBuiltin = asBoolean(values.get("isBuiltin"));
            if (values.containsKey("isPolicyMethod")) n.isPolicyMethod = asBoolean(values.get("isPolicyMethod"));
            return;
        }

        if (node instanceof Param) {
            Param n = (Param) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("type")) n.type = (String) values.get("type");
            if (values.containsKey("defaultValue")) n.defaultValue = (Expr) values.get("defaultValue");
            if (values.containsKey("hasDefaultValue")) n.hasDefaultValue = asBoolean(values.get("hasDefaultValue"));
            if (values.containsKey("typeInferred")) n.typeInferred = asBoolean(values.get("typeInferred"));
            if (values.containsKey("isLambdaParameter")) n.isLambdaParameter = asBoolean(values.get("isLambdaParameter"));
            if (values.containsKey("isTupleDestructuring")) n.isTupleDestructuring = asBoolean(values.get("isTupleDestructuring"));
            if (values.containsKey("tupleElements")) n.tupleElements = castList(values.get("tupleElements"));
            return;
        }

        if (node instanceof Constructor) {
            Constructor n = (Constructor) node;
            if (values.containsKey("parameters")) n.parameters = castList(values.get("parameters"));
            if (values.containsKey("body")) n.body = castList(values.get("body"));
            return;
        }

        if (node instanceof ConstructorCall) {
            ConstructorCall n = (ConstructorCall) node;
            if (values.containsKey("className")) n.className = (String) values.get("className");
            if (values.containsKey("arguments")) n.arguments = castList(values.get("arguments"));
            if (values.containsKey("argNames")) n.argNames = castList(values.get("argNames"));
            return;
        }

        if (node instanceof Policy) {
            Policy n = (Policy) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("visibility")) n.visibility = (cod.lexer.TokenType.Keyword) values.get("visibility");
            if (values.containsKey("methods")) n.methods = castList(values.get("methods"));
            if (values.containsKey("sourceUnit")) n.sourceUnit = (String) values.get("sourceUnit");
            if (values.containsKey("composedPolicies")) n.composedPolicies = castList(values.get("composedPolicies"));
            return;
        }

        if (node instanceof PolicyMethod) {
            PolicyMethod n = (PolicyMethod) node;
            if (values.containsKey("methodName")) n.methodName = (String) values.get("methodName");
            if (values.containsKey("parameters")) n.parameters = castList(values.get("parameters"));
            if (values.containsKey("returnSlots")) n.returnSlots = castList(values.get("returnSlots"));
            return;
        }

        if (node instanceof Block) {
            Block n = (Block) node;
            if (values.containsKey("statements")) n.statements = castList(values.get("statements"));
            return;
        }

        if (node instanceof Assignment) {
            Assignment n = (Assignment) node;
            if (values.containsKey("left")) n.left = (Expr) values.get("left");
            if (values.containsKey("right")) n.right = (Expr) values.get("right");
            if (values.containsKey("isDeclaration")) n.isDeclaration = asBoolean(values.get("isDeclaration"));
            return;
        }

        if (node instanceof Var) {
            Var n = (Var) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("value")) n.value = (Expr) values.get("value");
            if (values.containsKey("explicitType")) n.explicitType = (String) values.get("explicitType");
            return;
        }

        if (node instanceof StmtIf) {
            StmtIf n = (StmtIf) node;
            if (values.containsKey("condition")) n.condition = (Expr) values.get("condition");
            if (values.containsKey("thenBlock")) n.thenBlock = (Block) values.get("thenBlock");
            if (values.containsKey("elseBlock")) n.elseBlock = (Block) values.get("elseBlock");
            return;
        }

        if (node instanceof ExprIf) {
            ExprIf n = (ExprIf) node;
            if (values.containsKey("condition")) n.condition = (Expr) values.get("condition");
            if (values.containsKey("thenExpr")) n.thenExpr = (Expr) values.get("thenExpr");
            if (values.containsKey("elseExpr")) n.elseExpr = (Expr) values.get("elseExpr");
            return;
        }

        if (node instanceof For) {
            For n = (For) node;
            if (values.containsKey("iterator")) n.iterator = (String) values.get("iterator");
            if (values.containsKey("range")) n.range = (Range) values.get("range");
            if (values.containsKey("arraySource")) n.arraySource = (Expr) values.get("arraySource");
            if (values.containsKey("body")) n.body = (Block) values.get("body");
            return;
        }

        if (node instanceof Range) {
            Range n = (Range) node;
            if (values.containsKey("step")) n.step = (Expr) values.get("step");
            if (values.containsKey("start")) n.start = (Expr) values.get("start");
            if (values.containsKey("end")) n.end = (Expr) values.get("end");
            return;
        }

        if (node instanceof Tuple) {
            Tuple n = (Tuple) node;
            if (values.containsKey("elements")) n.elements = castList(values.get("elements"));
            return;
        }

        if (node instanceof ReturnSlotAssignment) {
            ReturnSlotAssignment n = (ReturnSlotAssignment) node;
            if (values.containsKey("variableNames")) n.variableNames = castList(values.get("variableNames"));
            if (values.containsKey("methodCall")) n.methodCall = (MethodCall) values.get("methodCall");
            if (values.containsKey("lambda")) n.lambda = (Lambda) values.get("lambda");
            return;
        }

        if (node instanceof SlotDeclaration) {
            SlotDeclaration n = (SlotDeclaration) node;
            if (values.containsKey("slotNames")) n.slotNames = castList(values.get("slotNames"));
            return;
        }

        if (node instanceof SlotAssignment) {
            SlotAssignment n = (SlotAssignment) node;
            if (values.containsKey("slotName")) n.slotName = (String) values.get("slotName");
            if (values.containsKey("value")) n.value = (Expr) values.get("value");
            return;
        }

        if (node instanceof MultipleSlotAssignment) {
            MultipleSlotAssignment n = (MultipleSlotAssignment) node;
            if (values.containsKey("assignments")) n.assignments = castList(values.get("assignments"));
            return;
        }

        if (node instanceof BinaryOp) {
            BinaryOp n = (BinaryOp) node;
            if (values.containsKey("left")) n.left = (Expr) values.get("left");
            if (values.containsKey("op")) n.op = (String) values.get("op");
            if (values.containsKey("right")) n.right = (Expr) values.get("right");
            return;
        }

        if (node instanceof Unary) {
            Unary n = (Unary) node;
            if (values.containsKey("op")) n.op = (String) values.get("op");
            if (values.containsKey("operand")) n.operand = (Expr) values.get("operand");
            return;
        }

        if (node instanceof TypeCast) {
            TypeCast n = (TypeCast) node;
            if (values.containsKey("targetType")) n.targetType = (String) values.get("targetType");
            if (values.containsKey("expression")) n.expression = (Expr) values.get("expression");
            return;
        }

        if (node instanceof MethodCall) {
            MethodCall n = (MethodCall) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("qualifiedName")) n.qualifiedName = (String) values.get("qualifiedName");
            if (values.containsKey("arguments")) n.arguments = castList(values.get("arguments"));
            if (values.containsKey("slotNames")) n.slotNames = castList(values.get("slotNames"));
            if (values.containsKey("argNames")) n.argNames = castList(values.get("argNames"));
            if (values.containsKey("isSuperCall")) n.isSuperCall = asBoolean(values.get("isSuperCall"));
            if (values.containsKey("isGlobal")) n.isGlobal = asBoolean(values.get("isGlobal"));
            if (values.containsKey("target")) n.target = (Expr) values.get("target");
            if (values.containsKey("isSingleSlotCall")) n.isSingleSlotCall = asBoolean(values.get("isSingleSlotCall"));
            if (values.containsKey("isSelfCall")) n.isSelfCall = asBoolean(values.get("isSelfCall"));
            if (values.containsKey("selfCallLevel")) n.selfCallLevel = asInteger(values.get("selfCallLevel"));
            if (values.containsKey("selfCallLevelConstantName")) n.selfCallLevelConstantName = (String) values.get("selfCallLevelConstantName");
            return;
        }

        if (node instanceof Array) {
            Array n = (Array) node;
            if (values.containsKey("elements")) n.elements = castList(values.get("elements"));
            if (values.containsKey("elementType")) n.elementType = (String) values.get("elementType");
            return;
        }

        if (node instanceof IndexAccess) {
            IndexAccess n = (IndexAccess) node;
            if (values.containsKey("array")) n.array = (Expr) values.get("array");
            if (values.containsKey("index")) n.index = (Expr) values.get("index");
            return;
        }

        if (node instanceof RangeIndex) {
            RangeIndex n = (RangeIndex) node;
            if (values.containsKey("step")) n.step = (Expr) values.get("step");
            if (values.containsKey("start")) n.start = (Expr) values.get("start");
            if (values.containsKey("end")) n.end = (Expr) values.get("end");
            return;
        }

        if (node instanceof MultiRangeIndex) {
            MultiRangeIndex n = (MultiRangeIndex) node;
            if (values.containsKey("ranges")) n.ranges = castList(values.get("ranges"));
            return;
        }

        if (node instanceof EqualityChain) {
            EqualityChain n = (EqualityChain) node;
            if (values.containsKey("left")) n.left = (Expr) values.get("left");
            if (values.containsKey("operator")) n.operator = (String) values.get("operator");
            if (values.containsKey("isAllChain")) n.isAllChain = asBoolean(values.get("isAllChain"));
            if (values.containsKey("chainArguments")) n.chainArguments = castList(values.get("chainArguments"));
            return;
        }

        if (node instanceof BooleanChain) {
            BooleanChain n = (BooleanChain) node;
            if (values.containsKey("isAll")) n.isAll = asBoolean(values.get("isAll"));
            if (values.containsKey("expressions")) n.expressions = castList(values.get("expressions"));
            return;
        }

        if (node instanceof Slot) {
            Slot n = (Slot) node;
            if (values.containsKey("name")) n.name = (String) values.get("name");
            if (values.containsKey("type")) n.type = (String) values.get("type");
            return;
        }

        if (node instanceof Lambda) {
            Lambda n = (Lambda) node;
            if (values.containsKey("parameters")) n.parameters = castList(values.get("parameters"));
            if (values.containsKey("returnSlots")) n.returnSlots = castList(values.get("returnSlots"));
            if (values.containsKey("body")) n.body = (Stmt) values.get("body");
            if (values.containsKey("expressionBody")) n.expressionBody = (Expr) values.get("expressionBody");
            if (values.containsKey("inferParameters")) n.inferParameters = asBoolean(values.get("inferParameters"));
            return;
        }

        if (node instanceof PropertyAccess) {
            PropertyAccess n = (PropertyAccess) node;
            if (values.containsKey("left")) n.left = (Expr) values.get("left");
            if (values.containsKey("right")) n.right = (Expr) values.get("right");
            return;
        }

        if (node instanceof ArgumentList) {
            ArgumentList n = (ArgumentList) node;
            if (values.containsKey("arguments")) n.arguments = castList(values.get("arguments"));
        }
    }

    private static boolean asBoolean(Object value) {
        return value != null && ((Boolean) value).booleanValue();
    }

    private static Integer asInteger(Object value) {
        return value != null ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object value) {
        return (List<T>) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> castMap(Object value) {
        return (Map<String, T>) value;
    }
}
