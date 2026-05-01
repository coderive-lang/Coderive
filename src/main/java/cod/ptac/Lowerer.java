package cod.ptac;

import cod.ast.node.*;
import cod.range.pattern.ConditionalPattern;
import cod.range.pattern.SequencePattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Lowerer {
    private int tempCounter = 0;
    private int patternCounter = 0;
    private int lambdaCounter = 0;

    public Unit lower(String unitName, Type type) {
        resetCounters();
        Unit unit = new Unit();
        unit.unitName = unitName;
        unit.className = type != null ? type.name : null;

        if (type == null || type.methods == null) {
            return unit;
        }

        for (Method method : type.methods) {
            unit.functions.add(lowerMethod(method, unit));
        }
        if (findFunction(unit, "main") != null) {
            unit.entryFunction = "main";
        } else if (!unit.functions.isEmpty()) {
            unit.entryFunction = unit.functions.get(0).name;
        }

        return unit;
    }

    private void resetCounters() {
        tempCounter = 0;
        patternCounter = 0;
        lambdaCounter = 0;
    }

    private Function lowerMethod(Method method, Unit unit) {
        Function fn = new Function();
        fn.name = method != null ? method.methodName : "anonymous";
        if (method != null && method.parameters != null) {
            for (Param param : method.parameters) {
                fn.parameters.add(param.name);
            }
        }
        if (method == null || method.body == null) {
            return fn;
        }

        for (Stmt stmt : method.body) {
            lowerStmt(stmt, fn, unit);
        }
        fn.instructions.add(new Instruction(
            Opcode.RETURN,
            null,
            Arrays.asList(Operand.immediate(null))
        ));
        return fn;
    }

    private void lowerStmt(Stmt stmt, Function fn, Unit unit) {
        if (stmt == null) return;

        if (stmt instanceof Var) {
            Var var = (Var) stmt;
            Operand value = lowerExpr(var.value, fn, unit);
            fn.instructions.add(new Instruction(
                Opcode.ASSIGN,
                var.name,
                Arrays.asList(value)
            ));
            return;
        }

        if (stmt instanceof Assignment) {
            Assignment assign = (Assignment) stmt;
            Operand rhs = lowerExpr(assign.right, fn, unit);
            if (assign.left instanceof Identifier) {
                fn.instructions.add(new Instruction(
                    Opcode.ASSIGN,
                    ((Identifier) assign.left).name,
                    Arrays.asList(rhs)
                ));
            } else if (assign.left instanceof IndexAccess) {
                IndexAccess access = (IndexAccess) assign.left;
                Operand arr = lowerExpr(access.array, fn, unit);
                Operand idx = lowerExpr(access.index, fn, unit);
                fn.instructions.add(new Instruction(
                    Opcode.LAZY_SET,
                    null,
                    Arrays.asList(arr, idx, rhs)
                ));
            }
            return;
        }

        if (stmt instanceof SlotAssignment) {
            SlotAssignment slot = (SlotAssignment) stmt;
            fn.instructions.add(new Instruction(
                Opcode.SLOT_SET,
                null,
                Arrays.asList(
                    Operand.slot(slot.slotName),
                    lowerExpr(slot.value, fn, unit)
                )
            ));
            return;
        }

        if (stmt instanceof MultipleSlotAssignment) {
            MultipleSlotAssignment multi = (MultipleSlotAssignment) stmt;
            if (multi.assignments != null) {
                for (SlotAssignment slot : multi.assignments) {
                    lowerStmt(slot, fn, unit);
                }
            }
            return;
        }

        if (stmt instanceof ReturnSlotAssignment) {
            ReturnSlotAssignment ret = (ReturnSlotAssignment) stmt;
            if (ret.methodCall != null) {
                Operand result = lowerExpr(ret.methodCall, fn, unit);
                fn.instructions.add(new Instruction(
                    Opcode.SLOT_UNPACK,
                    null,
                    Arrays.asList(result)
                ));
            } else if (ret.lambda != null) {
                Operand lambdaReg = lowerExpr(ret.lambda, fn, unit);
                fn.instructions.add(new Instruction(
                    Opcode.SLOT_UNPACK,
                    null,
                    Arrays.asList(lambdaReg)
                ));
            }
            return;
        }

        if (stmt instanceof For) {
            lowerFor((For) stmt, fn, unit);
            return;
        }

        if (stmt instanceof StmtIf) {
            StmtIf ifStmt = (StmtIf) stmt;
            String thenLabel = "L_then_" + nextTemp();
            String endLabel = "L_end_" + nextTemp();
            Operand cond = lowerExpr(ifStmt.condition, fn, unit);
            fn.instructions.add(new Instruction(
                Opcode.BRANCH_IF,
                null,
                Arrays.asList(cond, Operand.label(thenLabel))
            ));
            if (ifStmt.elseBlock != null && ifStmt.elseBlock.statements != null) {
                for (Stmt elseStmt : ifStmt.elseBlock.statements) {
                    lowerStmt(elseStmt, fn, unit);
                }
            }
            fn.instructions.add(new Instruction(
                Opcode.BRANCH,
                null,
                Arrays.asList(Operand.label(endLabel))
            ));
            fn.instructions.add(new Instruction(
                Opcode.NOP,
                thenLabel,
                new ArrayList<Operand>()
            ));
            if (ifStmt.thenBlock != null && ifStmt.thenBlock.statements != null) {
                for (Stmt thenStmt : ifStmt.thenBlock.statements) {
                    lowerStmt(thenStmt, fn, unit);
                }
            }
            fn.instructions.add(new Instruction(
                Opcode.NOP,
                endLabel,
                new ArrayList<Operand>()
            ));
            return;
        }

        if (stmt instanceof Block) {
            Block block = (Block) stmt;
            if (block.statements != null) {
                for (Stmt nested : block.statements) {
                    lowerStmt(nested, fn, unit);
                }
            }
            return;
        }

        if (stmt instanceof Exit) {
            fn.instructions.add(new Instruction(
                Opcode.RETURN,
                null,
                Arrays.asList(Operand.immediate(null))
            ));
            return;
        }

        if (stmt instanceof Expr) {
            lowerExpr((Expr) stmt, fn, unit);
            return;
        }

        fn.instructions.add(new Instruction(Opcode.NOP, null, new ArrayList<Operand>()));
    }

    private void lowerFor(For node, Function fn, Unit unit) {
        if (node == null || node.range == null) return;

        String rangeReg = nextPattern();
        Opcode rangeOpcode = selectRangeOpcode(node.range);
        List<Operand> rangeOps = new ArrayList<Operand>();
        rangeOps.add(lowerExpr(node.range.start, fn, unit));
        rangeOps.add(lowerExpr(node.range.end, fn, unit));
        if (node.range.step != null) {
            rangeOps.add(lowerExpr(node.range.step, fn, unit));
        }
        fn.instructions.add(new Instruction(rangeOpcode, rangeReg, rangeOps));

        List<Stmt> body = node.body != null ? node.body.statements : null;
        if (body == null || body.isEmpty()) return;

        SequencePattern.Pattern seq = SequencePattern.extract(body, node.iterator);
        ConditionalPattern cond = null;
        if (body.size() == 1 && body.get(0) instanceof StmtIf) {
            cond = ConditionalPattern.extract((StmtIf) body.get(0), node.iterator);
        }

        if (seq != null && seq.isOptimizable() && cond != null && cond.isOptimizable()) {
            String condLambda = lowerConditionLambda(cond, unit);
            String mapLambda = lowerSequenceLambda(seq, unit);
            fn.instructions.add(new Instruction(
                Opcode.FILTER_MAP,
                nextPattern(),
                Arrays.asList(
                    Operand.register(rangeReg),
                    Operand.function(condLambda),
                    Operand.function(mapLambda)
                )
            ));
            return;
        }

        if (cond != null && cond.isOptimizable()) {
            String condLambda = lowerConditionLambda(cond, unit);
            fn.instructions.add(new Instruction(
                Opcode.FILTER,
                nextPattern(),
                Arrays.asList(Operand.register(rangeReg), Operand.function(condLambda))
            ));
            return;
        }

        if (seq != null && seq.isOptimizable()) {
            String lambdaName = lowerSequenceLambda(seq, unit);
            fn.instructions.add(new Instruction(
                Opcode.MAP,
                nextPattern(),
                Arrays.asList(Operand.register(rangeReg), Operand.function(lambdaName))
            ));
            return;
        }

        for (Stmt stmt : body) {
            lowerStmt(stmt, fn, unit);
        }
    }

    private String lowerConditionLambda(ConditionalPattern pattern, Unit unit) {
        String lambdaName = nextLambdaName("cond");
        Function lambda = new Function();
        lambda.name = lambdaName;
        lambda.lambdaBlock = true;
        lambda.parameters.add(pattern.indexVar != null ? pattern.indexVar : "p0");

        if (pattern.branches != null && !pattern.branches.isEmpty()) {
            ConditionalPattern.Branch first = pattern.branches.get(0);
            if (first != null && first.condition != null) {
                Operand condition = lowerExpr(first.condition, lambda, unit);
                lambda.instructions.add(new Instruction(
                    Opcode.RETURN,
                    null,
                    Arrays.asList(condition)
                ));
            }
        }
        if (lambda.instructions.isEmpty()) {
            lambda.instructions.add(new Instruction(
                Opcode.RETURN,
                null,
                Arrays.asList(Operand.immediate(Boolean.TRUE))
            ));
        }
        unit.functions.add(lambda);
        return lambdaName;
    }

    private String lowerSequenceLambda(SequencePattern.Pattern pattern, Unit unit) {
        String lambdaName = nextLambdaName("seq");
        Function lambda = new Function();
        lambda.name = lambdaName;
        lambda.lambdaBlock = true;
        lambda.parameters.add(pattern.indexVar != null ? pattern.indexVar : "p0");

        if (pattern.steps != null) {
            for (SequencePattern.Step step : pattern.steps) {
                if (step == null) {
                    continue;
                }
                Operand value = lowerExpr(step.expression, lambda, unit);
                if (step.tempVar != null) {
                    lambda.instructions.add(new Instruction(
                        Opcode.ASSIGN,
                        step.tempVar,
                        Arrays.asList(value)
                    ));
                } else {
                    lambda.instructions.add(new Instruction(
                        Opcode.RETURN,
                        null,
                        Arrays.asList(value)
                    ));
                }
            }
        }
        if (lambda.instructions.isEmpty()) {
            lambda.instructions.add(new Instruction(
                Opcode.RETURN,
                null,
                Arrays.asList(Operand.immediate(null))
            ));
        }
        unit.functions.add(lambda);
        return lambdaName;
    }

    private Operand lowerExpr(Expr expr, Function fn, Unit unit) {
        if (expr == null) return Operand.immediate(null);

        if (expr instanceof IntLiteral) return Operand.immediate(((IntLiteral) expr).value);
        if (expr instanceof FloatLiteral) return Operand.immediate(((FloatLiteral) expr).value);
        if (expr instanceof BoolLiteral) return Operand.immediate(((BoolLiteral) expr).value);
        if (expr instanceof TextLiteral) return Operand.immediate(((TextLiteral) expr).value);
        if (expr instanceof NoneLiteral) return Operand.immediate(null);
        if (expr instanceof Identifier) return Operand.register(((Identifier) expr).name);

        if (expr instanceof Range) {
            Range range = (Range) expr;
            String dest = nextPattern();
            Opcode op = selectRangeOpcode(range);
            List<Operand> ops = new ArrayList<Operand>();
            ops.add(lowerExpr(range.start, fn, unit));
            ops.add(lowerExpr(range.end, fn, unit));
            if (range.step != null) ops.add(lowerExpr(range.step, fn, unit));
            fn.instructions.add(new Instruction(op, dest, ops));
            return Operand.register(dest);
        }

        if (expr instanceof IndexAccess) {
            IndexAccess access = (IndexAccess) expr;
            String dest = nextTemp();
            fn.instructions.add(new Instruction(
                Opcode.LAZY_GET,
                dest,
                Arrays.asList(
                    lowerExpr(access.array, fn, unit),
                    lowerExpr(access.index, fn, unit)
                )
            ));
            return Operand.register(dest);
        }

        if (expr instanceof BinaryOp) {
            BinaryOp binary = (BinaryOp) expr;
            Operand left = lowerExpr(binary.left, fn, unit);
            Operand right = lowerExpr(binary.right, fn, unit);
            String dest = nextTemp();
            fn.instructions.add(new Instruction(
                mapBinary(binary.op),
                dest,
                Arrays.asList(left, right)
            ));
            return Operand.register(dest);
        }

        if (expr instanceof MethodCall) {
            MethodCall call = (MethodCall) expr;
            List<Operand> ops = new ArrayList<Operand>();
            if (call.isSelfCall) {
                for (Expr arg : call.arguments) {
                    ops.add(lowerExpr(arg, fn, unit));
                }
                String dest = nextTemp();
                fn.instructions.add(new Instruction(Opcode.SELF, dest, ops));
                return Operand.register(dest);
            }

            ops.add(Operand.function(call.name));
            if (call.arguments != null) {
                for (Expr arg : call.arguments) {
                    ops.add(lowerExpr(arg, fn, unit));
                }
            }
            String dest = nextTemp();
            fn.instructions.add(new Instruction(Opcode.CALL, dest, ops));
            return Operand.register(dest);
        }

        if (expr instanceof Lambda) {
            Lambda lambdaNode = (Lambda) expr;
            String lambdaName = nextLambdaName("inline");
            Function lambda = new Function();
            lambda.name = lambdaName;
            lambda.lambdaBlock = true;
            if (lambdaNode.parameters != null) {
                for (Param param : lambdaNode.parameters) {
                    lambda.parameters.add(param.name);
                }
            }
            if (lambdaNode.expressionBody != null) {
                Operand val = lowerExpr(lambdaNode.expressionBody, lambda, unit);
                lambda.instructions.add(new Instruction(Opcode.RETURN, null, Arrays.asList(val)));
            } else if (lambdaNode.body != null) {
                lowerStmt(lambdaNode.body, lambda, unit);
            }
            if (lambda.instructions.isEmpty()) {
                lambda.instructions.add(new Instruction(
                    Opcode.RETURN,
                    null,
                    Arrays.asList(Operand.immediate(null))
                ));
            }
            unit.functions.add(lambda);
            return Operand.function(lambdaName);
        }

        return Operand.identifier(String.valueOf(expr));
    }

    private Opcode selectRangeOpcode(Range range) {
        if (range != null && (range.start instanceof TextLiteral || range.end instanceof TextLiteral)) {
            return range.step == null ? Opcode.RANGE_L : Opcode.RANGE_LS;
        }
        return range != null && range.step != null ? Opcode.RANGE_S : Opcode.RANGE;
    }

    private Opcode mapBinary(String op) {
        if ("+".equals(op)) return Opcode.ADD;
        if ("-".equals(op)) return Opcode.SUB;
        if ("*".equals(op)) return Opcode.MUL;
        if ("/".equals(op)) return Opcode.DIV;
        if ("%".equals(op)) return Opcode.MOD;
        if ("==".equals(op)) return Opcode.EQ;
        if ("!=".equals(op)) return Opcode.NE;
        if (">".equals(op)) return Opcode.GT;
        if ("<".equals(op)) return Opcode.LT;
        if (">=".equals(op)) return Opcode.GTE;
        if ("<=".equals(op)) return Opcode.LTE;
        return Opcode.NOP;
    }

    private String nextTemp() {
        return "t" + (tempCounter++);
    }

    private String nextPattern() {
        return "p" + (patternCounter++);
    }

    private String nextLambdaName(String prefix) {
        return "lambda$" + prefix + "$" + (lambdaCounter++);
    }

    private Function findFunction(Unit unit, String name) {
        if (unit == null || unit.functions == null) return null;
        for (Function fn : unit.functions) {
            if (fn != null && name.equals(fn.name)) return fn;
        }
        return null;
    }
}
