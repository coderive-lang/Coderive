package cod.ptac;

import cod.error.ProgramError;
import cod.interpreter.Interpreter;
import cod.ast.node.Program;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Executor {
    private final Options options;
    private static final Object FALLBACK_SENTINEL = new Object();

    private static final class RuntimeState {
        final Map<Object, Object> memory = new HashMap<Object, Object>();
        final Map<String, Object> slots = new HashMap<String, Object>();
    }

    private static final class FastRegisterMap extends AbstractMap<String, Object> {
        private final Map<String, Integer> indexByName = new HashMap<String, Integer>();
        private final Object[] values;
        private final Map<String, Object> overflow = new HashMap<String, Object>();

        FastRegisterMap(Function function) {
            int index = 0;
            if (function != null && function.parameters != null) {
                for (String parameter : function.parameters) {
                    if (parameter != null && !indexByName.containsKey(parameter)) {
                        indexByName.put(parameter, Integer.valueOf(index++));
                    }
                }
            }
            if (function != null && function.instructions != null) {
                for (Instruction instruction : function.instructions) {
                    if (instruction == null) continue;
                    if (instruction.dest != null && !indexByName.containsKey(instruction.dest)) {
                        indexByName.put(instruction.dest, Integer.valueOf(index++));
                    }
                    if (instruction.operands == null) continue;
                    for (Operand operand : instruction.operands) {
                        if (operand == null) continue;
                        if (operand.kind == OperandKind.REGISTER && operand.value instanceof String) {
                            String name = (String) operand.value;
                            if (!indexByName.containsKey(name)) {
                                indexByName.put(name, Integer.valueOf(index++));
                            }
                        }
                    }
                }
            }
            this.values = new Object[index];
        }

        @Override
        public Object get(Object key) {
            if (!(key instanceof String)) return null;
            String name = (String) key;
            Integer index = indexByName.get(name);
            if (index != null) {
                return values[index.intValue()];
            }
            return overflow.get(name);
        }

        @Override
        public boolean containsKey(Object key) {
            if (!(key instanceof String)) return false;
            String name = (String) key;
            return indexByName.containsKey(name) || overflow.containsKey(name);
        }

        @Override
        public Object put(String key, Object value) {
            if (key == null) return null;
            Integer index = indexByName.get(key);
            if (index != null) {
                int idx = index.intValue();
                Object previous = values[idx];
                values[idx] = value;
                return previous;
            }
            return overflow.put(key, value);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            Set<Entry<String, Object>> entries = new HashSet<Entry<String, Object>>();
            for (Map.Entry<String, Integer> item : indexByName.entrySet()) {
                int idx = item.getValue().intValue();
                entries.add(new AbstractMap.SimpleEntry<String, Object>(item.getKey(), values[idx]));
            }
            entries.addAll(overflow.entrySet());
            return entries;
        }
    }

    private static final class Range {
        final BigInteger start;
        final BigInteger end;
        final BigInteger step;

        Range(BigInteger start, BigInteger end, BigInteger step) {
            this.start = start;
            this.end = end;
            this.step = step;
        }
    }

    private static final class ExecutionResult {
        final Object value;
        final Integer nextPc;
        final boolean returned;
        final boolean fallback;

        ExecutionResult(Object value, Integer nextPc, boolean returned, boolean fallback) {
            this.value = value;
            this.nextPc = nextPc;
            this.returned = returned;
            this.fallback = fallback;
        }

        static ExecutionResult normal(Object value) {
            return new ExecutionResult(value, null, false, false);
        }

        static ExecutionResult jump(int nextPc) {
            return new ExecutionResult(null, Integer.valueOf(nextPc), false, false);
        }

        static ExecutionResult returned(Object value) {
            return new ExecutionResult(value, null, true, false);
        }

        static ExecutionResult fallback() {
            return new ExecutionResult(null, null, false, true);
        }
    }

    public Executor(Options options) {
        this.options = options != null ? options : Options.current();
    }

    public Object execute(Artifact artifact, Interpreter fallbackInterpreter) {
        if (artifact == null) {
            throw new ProgramError("Cannot execute null CodP-TAC artifact");
        }
        RuntimeState state = new RuntimeState();

        if (artifact.unit == null || artifact.unit.functions == null || artifact.unit.functions.isEmpty()) {
            return fallback(artifact, fallbackInterpreter, "No executable CodP-TAC unit in artifact", state);
        }

        Map<String, Function> functionIndex = indexFunctions(artifact.unit);
        Function entry = findEntry(artifact.unit, functionIndex);
        if (entry == null) {
            return fallback(artifact, fallbackInterpreter, "No entry function found in CodP-TAC unit", state);
        }
        Object result = executeFunction(
            artifact.unit,
            entry,
            new ArrayList<Object>(),
            fallbackInterpreter,
            artifact,
            functionIndex,
            state
        );
        return result == FALLBACK_SENTINEL ? null : result;
    }

    private Object executeFunction(
        Unit unit,
        Function function,
        List<Object> args,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        Map<String, Object> registers = new FastRegisterMap(function);
        if (function.parameters != null) {
            for (int i = 0; i < function.parameters.size(); i++) {
                Object arg = i < args.size() ? args.get(i) : null;
                registers.put(function.parameters.get(i), arg);
            }
        }

        if (function.instructions == null) return null;
        Map<String, Integer> labels = indexLabels(function);
        int pc = 0;
        while (pc < function.instructions.size()) {
            Instruction inst = function.instructions.get(pc);
            if (inst == null) {
                pc++;
                continue;
            }
            ExecutionResult result = runInstruction(
                unit,
                function,
                inst,
                registers,
                fallbackInterpreter,
                artifact,
                labels,
                pc,
                functionIndex,
                state
            );
            if (result.fallback) {
                return FALLBACK_SENTINEL;
            }
            if (result.returned) {
                return result.value;
            }
            if (result.nextPc != null) {
                pc = result.nextPc.intValue();
            } else {
                pc++;
            }
        }
        return null;
    }

    private ExecutionResult runInstruction(
        Unit unit,
        Function currentFunction,
        Instruction inst,
        Map<String, Object> registers,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Integer> labels,
        int currentPc,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        if (inst.opcode == Opcode.ASSIGN) {
            Object value = operandValue(inst.operands, 0, registers);
            registers.put(inst.dest, value);
            return ExecutionResult.normal(value);
        }

        if (isMath(inst.opcode)) {
            Object left = operandValue(inst.operands, 0, registers);
            Object right = operandValue(inst.operands, 1, registers);
            Object out = evaluateMath(inst.opcode, left, right);
            if (inst.dest != null) registers.put(inst.dest, out);
            return ExecutionResult.normal(out);
        }

        if (isCompare(inst.opcode)) {
            Object left = operandValue(inst.operands, 0, registers);
            Object right = operandValue(inst.operands, 1, registers);
            Boolean out = evaluateCompare(inst.opcode, left, right);
            if (inst.dest != null) registers.put(inst.dest, out);
            return ExecutionResult.normal(out);
        }

        if (inst.opcode == Opcode.RANGE
            || inst.opcode == Opcode.RANGE_Q
            || inst.opcode == Opcode.RANGE_S
            || inst.opcode == Opcode.RANGE_L
            || inst.opcode == Opcode.RANGE_LS) {
            Object start = operandValue(inst.operands, 0, registers);
            Object end = operandValue(inst.operands, 1, registers);
            if (!isNumericLike(start) || !isNumericLike(end)) {
                Object fallback = fallback(
                    artifact,
                    fallbackInterpreter,
                    "Non-numeric range bounds are not yet natively executed",
                    state
                );
                if (fallback == FALLBACK_SENTINEL) return ExecutionResult.fallback();
            }
            Object stepVal = inst.operands != null && inst.operands.size() > 2
                ? operandValue(inst.operands, 2, registers)
                : 1;
            Range range = new Range(toBigInt(start), toBigInt(end), toBigInt(stepVal));
            if (inst.dest != null) registers.put(inst.dest, range);
            return ExecutionResult.normal(range);
        }

        if (inst.opcode == Opcode.TAKE) {
            Range source = asRange(operandValue(inst.operands, 0, registers));
            BigInteger n = toBigInt(operandValue(inst.operands, 1, registers));
            List<BigInteger> out = take(source, n);
            if (inst.dest != null) registers.put(inst.dest, out);
            return ExecutionResult.normal(out);
        }

        if (inst.opcode == Opcode.NOP) {
            return ExecutionResult.normal(null);
        }

        if (inst.opcode == Opcode.BRANCH) {
            String label = asLabel(operandValue(inst.operands, 0, registers));
            Integer jumpTarget = label != null ? labels.get(label) : null;
            if (jumpTarget == null) {
                Object fallback = fallback(
                    artifact,
                    fallbackInterpreter,
                    "Unknown branch label: " + label,
                    state
                );
                if (fallback == FALLBACK_SENTINEL) return ExecutionResult.fallback();
            }
            return ExecutionResult.jump(jumpTarget.intValue());
        }

        if (inst.opcode == Opcode.BRANCH_IF) {
            Object condition = operandValue(inst.operands, 0, registers);
            if (isTruthy(condition)) {
                String label = asLabel(operandValue(inst.operands, 1, registers));
                Integer jumpTarget = label != null ? labels.get(label) : null;
                if (jumpTarget == null) {
                    Object fallback = fallback(
                        artifact,
                        fallbackInterpreter,
                        "Unknown branch-if label: " + label,
                        state
                    );
                    if (fallback == FALLBACK_SENTINEL) return ExecutionResult.fallback();
                }
                return ExecutionResult.jump(jumpTarget.intValue());
            }
            return ExecutionResult.normal(null);
        }

        if (inst.opcode == Opcode.LAZY_GET) {
            Object source = operandValue(inst.operands, 0, registers);
            BigInteger index = toBigInt(operandValue(inst.operands, 1, registers));
            Object out = lazyGet(source, index);
            if (inst.dest != null) registers.put(inst.dest, out);
            return ExecutionResult.normal(out);
        }

        if (inst.opcode == Opcode.LAZY_SET) {
            Object source = operandValue(inst.operands, 0, registers);
            BigInteger index = toBigInt(operandValue(inst.operands, 1, registers));
            Object value = operandValue(inst.operands, 2, registers);
            Object out = lazySet(source, index, value);
            if (out == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            return ExecutionResult.normal(out);
        }

        if (inst.opcode == Opcode.LAZY_SIZE) {
            Object source = operandValue(inst.operands, 0, registers);
            Object out = lazySize(source);
            if (inst.dest != null) registers.put(inst.dest, out);
            return ExecutionResult.normal(out);
        }

        if (inst.opcode == Opcode.LAZY_COMMIT) {
            return ExecutionResult.normal(null);
        }

        if (inst.opcode == Opcode.MAP) {
            Object mapped = runMap(
                unit,
                inst,
                registers,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (mapped == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            if (inst.dest != null) registers.put(inst.dest, mapped);
            return ExecutionResult.normal(mapped);
        }

        if (inst.opcode == Opcode.FILTER) {
            Object filtered = runFilter(
                unit,
                inst,
                registers,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (filtered == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            if (inst.dest != null) registers.put(inst.dest, filtered);
            return ExecutionResult.normal(filtered);
        }

        if (inst.opcode == Opcode.REDUCE) {
            Object reduced = runReduce(
                unit,
                inst,
                registers,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (reduced == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            if (inst.dest != null) registers.put(inst.dest, reduced);
            return ExecutionResult.normal(reduced);
        }

        if (inst.opcode == Opcode.FILTER_MAP) {
            Object filteredMapped = runFilterMap(
                unit,
                inst,
                registers,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (filteredMapped == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            if (inst.dest != null) registers.put(inst.dest, filteredMapped);
            return ExecutionResult.normal(filteredMapped);
        }

        if (inst.opcode == Opcode.SLOT_SET) {
            String slotName = String.valueOf(operandValue(inst.operands, 0, registers));
            Object value = operandValue(inst.operands, 1, registers);
            state.slots.put(slotName, value);
            if (inst.dest != null) registers.put(inst.dest, value);
            return ExecutionResult.normal(value);
        }

        if (inst.opcode == Opcode.SLOT_GET) {
            String slotName = String.valueOf(operandValue(inst.operands, 0, registers));
            Object value = state.slots.get(slotName);
            if (inst.dest != null) registers.put(inst.dest, value);
            return ExecutionResult.normal(value);
        }

        if (inst.opcode == Opcode.SLOT_UNPACK) {
            Object source = operandValue(inst.operands, 0, registers);
            if (source instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> sourceMap = (Map<Object, Object>) source;
                for (Map.Entry<Object, Object> entry : sourceMap.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        String key = (String) entry.getKey();
                        state.slots.put(key, entry.getValue());
                        registers.put(key, entry.getValue());
                    }
                }
            }
            return ExecutionResult.normal(source);
        }

        if (inst.opcode == Opcode.SLOT_RET) {
            return ExecutionResult.returned(new HashMap<String, Object>(state.slots));
        }

        if (inst.opcode == Opcode.SLOT_DIV) {
            return ExecutionResult.normal(null);
        }

        if (inst.opcode == Opcode.STORE) {
            Object address = operandValue(inst.operands, 0, registers);
            Object value = operandValue(inst.operands, 1, registers);
            state.memory.put(address, value);
            if (inst.dest != null) registers.put(inst.dest, value);
            return ExecutionResult.normal(value);
        }

        if (inst.opcode == Opcode.LOAD) {
            Object address = operandValue(inst.operands, 0, registers);
            Object value = state.memory.get(address);
            if (inst.dest != null) registers.put(inst.dest, value);
            return ExecutionResult.normal(value);
        }

        if (inst.opcode == Opcode.FILTER
            || inst.opcode == Opcode.SCAN
            || inst.opcode == Opcode.ZIP
            || inst.opcode == Opcode.WHERE
            || inst.opcode == Opcode.FILTER_MAP_REDUCE
            || inst.opcode == Opcode.LAZY_SLICE
            || inst.opcode == Opcode.ANCESTOR
            || inst.opcode == Opcode.SELF
            || inst.opcode == Opcode.TAIL_CALL
            || inst.opcode == Opcode.CLOSURE
            || inst.opcode == Opcode.FORMULA_SEQ
            || inst.opcode == Opcode.FORMULA_COND
            || inst.opcode == Opcode.FORMULA_RECUR
            || inst.opcode == Opcode.FORMULA_FUSE) {
            Object fallback = fallback(
                artifact,
                fallbackInterpreter,
                "Opcode not yet natively executed: " + inst.opcode,
                state
            );
            if (fallback == FALLBACK_SENTINEL) return ExecutionResult.fallback();
        }

        if (inst.opcode == Opcode.CALL) {
            String functionName = String.valueOf(operandValue(inst.operands, 0, registers));
            Function target = findFunction(functionIndex, functionName);
            if (target == null) {
                Object fallback = fallback(
                    artifact,
                    fallbackInterpreter,
                    "Unknown function: " + functionName,
                    state
                );
                if (fallback == FALLBACK_SENTINEL) return ExecutionResult.fallback();
            }
            List<Object> args = new ArrayList<Object>();
            for (int i = 1; i < inst.operands.size(); i++) {
                args.add(operandValue(inst.operands, i, registers));
            }
            Object result = executeFunction(
                unit,
                target,
                args,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (result == FALLBACK_SENTINEL) {
                return ExecutionResult.fallback();
            }
            if (inst.dest != null) registers.put(inst.dest, result);
            return ExecutionResult.normal(result);
        }

        if (inst.opcode == Opcode.RETURN) {
            return ExecutionResult.returned(operandValue(inst.operands, 0, registers));
        }

        return ExecutionResult.normal(null);
    }

    private Object fallback(
        Artifact artifact,
        Interpreter fallbackInterpreter,
        String reason,
        RuntimeState state
    ) {
        if (!options.isFallbackEnabled()) {
            throw new ProgramError("CodP-TAC execution failed without fallback: " + reason);
        }
        if (fallbackInterpreter != null) {
            Program currentProgram = fallbackInterpreter.getCurrentProgram();
            if (currentProgram != null) {
                fallbackInterpreter.run(currentProgram);
                return FALLBACK_SENTINEL;
            }
            if (artifact != null && artifact.typeSnapshot != null) {
                fallbackInterpreter.runType(artifact.typeSnapshot);
                return FALLBACK_SENTINEL;
            }
        }
        throw new ProgramError("CodP-TAC fallback unavailable: " + reason);
    }

    private Function findEntry(Unit unit, Map<String, Function> functionIndex) {
        if (unit.entryFunction != null) {
            Function explicit = findFunction(functionIndex, unit.entryFunction);
            if (explicit != null) return explicit;
        }
        return findFunction(functionIndex, "main");
    }

    private Map<String, Function> indexFunctions(Unit unit) {
        if (unit == null || unit.functions == null || unit.functions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Function> functions = new HashMap<String, Function>();
        for (Function function : unit.functions) {
            if (function == null || function.name == null) continue;
            functions.put(function.name, function);
        }
        return functions;
    }

    private Function findFunction(Map<String, Function> functionIndex, String name) {
        if (functionIndex == null || functionIndex.isEmpty() || name == null) return null;
        return functionIndex.get(name);
    }

    private Object operandValue(List<Operand> operands, int index, Map<String, Object> registers) {
        if (operands == null || index >= operands.size()) return null;
        Operand operand = operands.get(index);
        if (operand == null) return null;
        if (operand.kind == OperandKind.REGISTER && operand.value instanceof String) {
            return registers.get(operand.value);
        }
        return operand.value;
    }

    private Map<String, Integer> indexLabels(Function function) {
        if (function == null || function.instructions == null || function.instructions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> labels = new HashMap<String, Integer>();
        for (int i = 0; i < function.instructions.size(); i++) {
            Instruction inst = function.instructions.get(i);
            if (inst == null) continue;
            if (inst.opcode == Opcode.NOP && inst.dest != null) {
                labels.put(inst.dest, Integer.valueOf(i));
            }
        }
        return labels;
    }

    private String asLabel(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isMath(Opcode opcode) {
        return opcode == Opcode.ADD
            || opcode == Opcode.SUB
            || opcode == Opcode.MUL
            || opcode == Opcode.DIV
            || opcode == Opcode.MOD;
    }

    private boolean isCompare(Opcode opcode) {
        return opcode == Opcode.EQ
            || opcode == Opcode.NE
            || opcode == Opcode.GT
            || opcode == Opcode.LT
            || opcode == Opcode.GTE
            || opcode == Opcode.LTE;
    }

    private Object evaluateMath(Opcode opcode, Object a, Object b) {
        if (isFloatingLike(a) || isFloatingLike(b)) {
            double left = toDouble(a);
            double right = toDouble(b);
            if (opcode == Opcode.ADD) return Double.valueOf(left + right);
            if (opcode == Opcode.SUB) return Double.valueOf(left - right);
            if (opcode == Opcode.MUL) return Double.valueOf(left * right);
            if (opcode == Opcode.DIV) {
                if (right == 0.0d) {
                    throw new ProgramError("CodP-TAC division by zero");
                }
                return Double.valueOf(left / right);
            }
            if (opcode == Opcode.MOD) {
                if (right == 0.0d) {
                    throw new ProgramError("CodP-TAC modulo by zero");
                }
                return Double.valueOf(left % right);
            }
        }

        if (isLongLike(a) && isLongLike(b)) {
            long left = toLong(a);
            long right = toLong(b);
            if (opcode == Opcode.ADD && !willOverflowAdd(left, right)) {
                return Long.valueOf(left + right);
            }
            if (opcode == Opcode.SUB && !willOverflowSub(left, right)) {
                return Long.valueOf(left - right);
            }
            if (opcode == Opcode.MUL && !willOverflowMul(left, right)) {
                return Long.valueOf(left * right);
            }
            if (opcode == Opcode.DIV) {
                if (right == 0L) {
                    throw new ProgramError("CodP-TAC division by zero");
                }
                return Long.valueOf(left / right);
            }
            if (opcode == Opcode.MOD) {
                if (right == 0L) {
                    throw new ProgramError("CodP-TAC modulo by zero");
                }
                long modulus = right < 0L ? -right : right;
                return Long.valueOf(left % modulus);
            }
        }

        BigInteger left = toBigInt(a);
        BigInteger right = toBigInt(b);
        if (opcode == Opcode.ADD) return left.add(right);
        if (opcode == Opcode.SUB) return left.subtract(right);
        if (opcode == Opcode.MUL) return left.multiply(right);
        if (opcode == Opcode.DIV) {
            if (right.equals(BigInteger.ZERO)) {
                throw new ProgramError("CodP-TAC division by zero");
            }
            return left.divide(right);
        }
        if (opcode == Opcode.MOD) {
            if (right.equals(BigInteger.ZERO)) {
                throw new ProgramError("CodP-TAC modulo by zero");
            }
            return left.mod(right.abs());
        }
        return BigInteger.ZERO;
    }

    private Boolean evaluateCompare(Opcode opcode, Object a, Object b) {
        if (isFloatingLike(a) || isFloatingLike(b)) {
            double left = toDouble(a);
            double right = toDouble(b);
            int cmp = left < right ? -1 : (left > right ? 1 : 0);
            if (opcode == Opcode.EQ) return cmp == 0;
            if (opcode == Opcode.NE) return cmp != 0;
            if (opcode == Opcode.GT) return cmp > 0;
            if (opcode == Opcode.LT) return cmp < 0;
            if (opcode == Opcode.GTE) return cmp >= 0;
            if (opcode == Opcode.LTE) return cmp <= 0;
            return false;
        }
        if (isLongLike(a) && isLongLike(b)) {
            long left = toLong(a);
            long right = toLong(b);
            int cmp = left < right ? -1 : (left > right ? 1 : 0);
            if (opcode == Opcode.EQ) return cmp == 0;
            if (opcode == Opcode.NE) return cmp != 0;
            if (opcode == Opcode.GT) return cmp > 0;
            if (opcode == Opcode.LT) return cmp < 0;
            if (opcode == Opcode.GTE) return cmp >= 0;
            if (opcode == Opcode.LTE) return cmp <= 0;
            return false;
        }
        BigInteger left = toBigInt(a);
        BigInteger right = toBigInt(b);
        int cmp = left.compareTo(right);
        if (opcode == Opcode.EQ) return cmp == 0;
        if (opcode == Opcode.NE) return cmp != 0;
        if (opcode == Opcode.GT) return cmp > 0;
        if (opcode == Opcode.LT) return cmp < 0;
        if (opcode == Opcode.GTE) return cmp >= 0;
        if (opcode == Opcode.LTE) return cmp <= 0;
        return false;
    }

    private Range asRange(Object value) {
        if (value instanceof Range) return (Range) value;
        return new Range(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE);
    }

    private List<Object> asSequence(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            return list;
        }
        if (value instanceof Range) {
            return materializeRange((Range) value);
        }
        return null;
    }

    private List<Object> materializeRange(Range range) {
        List<Object> out = new ArrayList<Object>();
        if (range == null || range.step == null || range.step.equals(BigInteger.ZERO)) return out;
        BigInteger current = range.start;
        BigInteger step = range.step;
        while (true) {
            if (step.compareTo(BigInteger.ZERO) >= 0) {
                if (current.compareTo(range.end) > 0) break;
            } else {
                if (current.compareTo(range.end) < 0) break;
            }
            out.add(current);
            current = current.add(step);
        }
        return out;
    }

    private Object runMap(
        Unit unit,
        Instruction inst,
        Map<String, Object> registers,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        List<Object> source = asSequence(operandValue(inst.operands, 0, registers));
        if (source == null) {
            return fallback(artifact, fallbackInterpreter, "MAP source is not a sequence", state);
        }
        String mapperName = String.valueOf(operandValue(inst.operands, 1, registers));
        Function mapper = findFunction(functionIndex, mapperName);
        if (mapper == null) {
            return fallback(artifact, fallbackInterpreter, "MAP function not found: " + mapperName, state);
        }
        List<Object> out = new ArrayList<Object>(source.size());
        for (int i = 0; i < source.size(); i++) {
            Object element = source.get(i);
            List<Object> args = new ArrayList<Object>();
            args.add(element);
            Object mapped = executeFunction(
                unit,
                mapper,
                args,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (mapped == FALLBACK_SENTINEL) {
                return FALLBACK_SENTINEL;
            }
            out.add(mapped);
        }
        return out;
    }

    private Object runFilter(
        Unit unit,
        Instruction inst,
        Map<String, Object> registers,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        List<Object> source = asSequence(operandValue(inst.operands, 0, registers));
        if (source == null) {
            return fallback(artifact, fallbackInterpreter, "FILTER source is not a sequence", state);
        }
        String predicateName = String.valueOf(operandValue(inst.operands, 1, registers));
        Function predicate = findFunction(functionIndex, predicateName);
        if (predicate == null) {
            return fallback(
                artifact,
                fallbackInterpreter,
                "FILTER function not found: " + predicateName,
                state
            );
        }
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < source.size(); i++) {
            Object element = source.get(i);
            List<Object> args = new ArrayList<Object>();
            args.add(element);
            Object keep = executeFunction(
                unit,
                predicate,
                args,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (keep == FALLBACK_SENTINEL) {
                return FALLBACK_SENTINEL;
            }
            if (isTruthy(keep)) {
                out.add(element);
            }
        }
        return out;
    }

    private Object runReduce(
        Unit unit,
        Instruction inst,
        Map<String, Object> registers,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        List<Object> source = asSequence(operandValue(inst.operands, 0, registers));
        if (source == null) {
            return fallback(artifact, fallbackInterpreter, "REDUCE source is not a sequence", state);
        }
        if (source.isEmpty()) {
            return null;
        }
        String reducerName = String.valueOf(operandValue(inst.operands, 1, registers));
        Function reducer = findFunction(functionIndex, reducerName);
        if (reducer == null) {
            return fallback(
                artifact,
                fallbackInterpreter,
                "REDUCE function not found: " + reducerName,
                state
            );
        }
        Object accumulator = source.get(0);
        for (int i = 1; i < source.size(); i++) {
            List<Object> args = new ArrayList<Object>();
            args.add(accumulator);
            args.add(source.get(i));
            Object reduced = executeFunction(
                unit,
                reducer,
                args,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (reduced == FALLBACK_SENTINEL) {
                return FALLBACK_SENTINEL;
            }
            accumulator = reduced;
        }
        return accumulator;
    }

    private Object runFilterMap(
        Unit unit,
        Instruction inst,
        Map<String, Object> registers,
        Interpreter fallbackInterpreter,
        Artifact artifact,
        Map<String, Function> functionIndex,
        RuntimeState state
    ) {
        List<Object> source = asSequence(operandValue(inst.operands, 0, registers));
        if (source == null) {
            return fallback(artifact, fallbackInterpreter, "FILTER_MAP source is not a sequence", state);
        }
        String predicateName = String.valueOf(operandValue(inst.operands, 1, registers));
        String mapperName = String.valueOf(operandValue(inst.operands, 2, registers));
        Function predicate = findFunction(functionIndex, predicateName);
        Function mapper = findFunction(functionIndex, mapperName);
        if (predicate == null || mapper == null) {
            return fallback(
                artifact,
                fallbackInterpreter,
                "FILTER_MAP function not found: predicate=" + predicateName + ", mapper=" + mapperName,
                state
            );
        }
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < source.size(); i++) {
            Object element = source.get(i);
            List<Object> predicateArgs = new ArrayList<Object>();
            predicateArgs.add(element);
            Object keep = executeFunction(
                unit,
                predicate,
                predicateArgs,
                fallbackInterpreter,
                artifact,
                functionIndex,
                state
            );
            if (keep == FALLBACK_SENTINEL) {
                return FALLBACK_SENTINEL;
            }
            if (isTruthy(keep)) {
                List<Object> mapperArgs = new ArrayList<Object>();
                mapperArgs.add(element);
                Object mapped = executeFunction(
                    unit,
                    mapper,
                    mapperArgs,
                    fallbackInterpreter,
                    artifact,
                    functionIndex,
                    state
                );
                if (mapped == FALLBACK_SENTINEL) {
                    return FALLBACK_SENTINEL;
                }
                out.add(mapped);
            }
        }
        return out;
    }

    private Object lazyGet(Object source, BigInteger index) {
        if (source instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) source;
            int idx = normalizeListIndex(list.size(), index);
            return list.get(idx);
        }
        if (source instanceof Range) {
            Range range = (Range) source;
            BigInteger size = rangeSize(range);
            BigInteger normalized = normalizeRangeIndex(size, index);
            return range.start.add(range.step.multiply(normalized));
        }
        return null;
    }

    private Object lazySet(Object source, BigInteger index, Object value) {
        if (source instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) source;
            int idx = normalizeListIndex(list.size(), index);
            list.set(idx, value);
            return value;
        }
        return FALLBACK_SENTINEL;
    }

    private Object lazySize(Object source) {
        if (source instanceof List) {
            return Integer.valueOf(((List<?>) source).size());
        }
        if (source instanceof Range) {
            BigInteger size = rangeSize((Range) source);
            if (size.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                return Integer.valueOf(size.intValue());
            }
            return size;
        }
        return Integer.valueOf(0);
    }

    private int normalizeListIndex(int size, BigInteger index) {
        int idx = index.intValue();
        if (idx < 0) {
            idx = size + idx;
        }
        if (idx < 0 || idx >= size) {
            throw new ProgramError("Index: " + idx + ", Size: " + size);
        }
        return idx;
    }

    private BigInteger normalizeRangeIndex(BigInteger size, BigInteger index) {
        BigInteger idx = index;
        if (idx.compareTo(BigInteger.ZERO) < 0) {
            idx = size.add(idx);
        }
        if (idx.compareTo(BigInteger.ZERO) < 0 || idx.compareTo(size) >= 0) {
            throw new ProgramError("Index: " + idx + ", Size: " + size);
        }
        return idx;
    }

    private BigInteger rangeSize(Range range) {
        if (range == null || range.step == null || range.step.equals(BigInteger.ZERO)) {
            return BigInteger.ZERO;
        }
        boolean increasing = range.step.compareTo(BigInteger.ZERO) > 0;
        if (increasing && range.start.compareTo(range.end) > 0) return BigInteger.ZERO;
        if (!increasing && range.start.compareTo(range.end) < 0) return BigInteger.ZERO;

        BigInteger distance = increasing
            ? range.end.subtract(range.start)
            : range.start.subtract(range.end);
        BigInteger stride = range.step.abs();
        return distance.divide(stride).add(BigInteger.ONE);
    }

    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0d;
        if (value instanceof String) {
            String s = (String) value;
            return s.length() > 0 && !"false".equalsIgnoreCase(s);
        }
        if (value instanceof List) return !((List<?>) value).isEmpty();
        return true;
    }

    private boolean isNumericLike(Object value) {
        if (value == null) return false;
        if (value instanceof Number || value instanceof BigInteger) return true;
        try {
            new BigInteger(String.valueOf(value));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<BigInteger> take(Range range, BigInteger n) {
        List<BigInteger> out = new ArrayList<BigInteger>();
        if (range == null || n == null || n.compareTo(BigInteger.ZERO) <= 0) return out;

        BigInteger current = range.start;
        BigInteger remaining = n;
        while (remaining.compareTo(BigInteger.ZERO) > 0) {
            if (range.step.compareTo(BigInteger.ZERO) >= 0 && current.compareTo(range.end) > 0) break;
            if (range.step.compareTo(BigInteger.ZERO) < 0 && current.compareTo(range.end) < 0) break;
            out.add(current);
            current = current.add(range.step);
            remaining = remaining.subtract(BigInteger.ONE);
        }
        return out;
    }

    private BigInteger toBigInt(Object value) {
        if (value == null) return BigInteger.ZERO;
        if (value instanceof BigInteger) return (BigInteger) value;
        if (value instanceof Number) return BigInteger.valueOf(((Number) value).longValue());
        try {
            return new BigInteger(String.valueOf(value));
        } catch (Exception ignored) {
            return BigInteger.ZERO;
        }
    }

    private boolean isFloatingLike(Object value) {
        if (value instanceof Float || value instanceof Double) return true;
        if (value instanceof Number) return false;
        if (value == null) return false;
        String text = String.valueOf(value).trim();
        return text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0;
    }

    private boolean isLongLike(Object value) {
        if (value == null) return false;
        if (value instanceof Float || value instanceof Double) return false;
        if (value instanceof Number || value instanceof BigInteger) return true;
        try {
            Long.parseLong(String.valueOf(value).trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof BigInteger) return ((BigInteger) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0d;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private boolean willOverflowAdd(long a, long b) {
        return (b > 0L && a > Long.MAX_VALUE - b) || (b < 0L && a < Long.MIN_VALUE - b);
    }

    private boolean willOverflowSub(long a, long b) {
        return (b < 0L && a > Long.MAX_VALUE + b) || (b > 0L && a < Long.MIN_VALUE + b);
    }

    private boolean willOverflowMul(long a, long b) {
        if (a == 0L || b == 0L) return false;
        if (a == Long.MIN_VALUE && b == -1L) return true;
        if (b == Long.MIN_VALUE && a == -1L) return true;
        long result = a * b;
        return result / b != a;
    }
}
