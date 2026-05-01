package cod.interpreter.handler;

import cod.ast.node.*;
import cod.debug.DebugSystem;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.InterpreterVisitor;
import cod.math.AutoStackingNumber;
import cod.range.NaturalArray;
import cod.range.formula.AccumulationFormula;
import cod.range.formula.ConditionalFormula;
import cod.range.formula.SequenceFormula;
import cod.range.pattern.AccumulationPattern;
import cod.range.pattern.ConditionalPattern;
import cod.range.pattern.SequencePattern;

import java.util.*;

public class PatternHandler {
    
    public enum PatternType {
        CONDITIONAL,
        SEQUENCE,
        ARRAY_RECURRENCE,  // FREC for arrays
        VECTOR_RECURRENCE   // VEC for arrays
    }

    public static class PatternResult {
        public final PatternType type;
        public final Object pattern;  // Can be ConditionalPattern, SequencePattern, or AccumulationPattern
        public final Expr targetArray;

        public PatternResult(PatternType type, Object pattern, Expr targetArray) {
            if (type == null) throw new InternalError("PatternResult constructed with null type");
            if (pattern == null) throw new InternalError("PatternResult constructed with null pattern");
            this.type = type;
            this.pattern = pattern;
            this.targetArray = targetArray;
        }
    }

    private final InterpreterVisitor dispatcher;
    private final TypeHandler typeSystem;
    private final ExpressionHandler exprHandler;
    private final ArrayHandler arrayHandler;

    public PatternHandler(
        InterpreterVisitor dispatcher,
        TypeHandler typeSystem,
        ExpressionHandler exprHandler,
        ArrayHandler arrayHandler
    ) {
        if (dispatcher == null) throw new InternalError("PatternHandler dispatcher is null");
        if (typeSystem == null) throw new InternalError("PatternHandler typeSystem is null");
        if (exprHandler == null) throw new InternalError("PatternHandler exprHandler is null");
        if (arrayHandler == null) throw new InternalError("PatternHandler arrayHandler is null");
        this.dispatcher = dispatcher;
        this.typeSystem = typeSystem;
        this.exprHandler = exprHandler;
        this.arrayHandler = arrayHandler;
    }

    public Object applyPatterns(For node, List<PatternResult> patterns) {
        if (node == null) throw new InternalError("applyPatterns called with null node");
        if (patterns == null) throw new InternalError("applyPatterns called with null patterns");

        try {
            // Check if all patterns are VECTOR_RECURRENCE (using AccumulationPattern)
            if (isVectorRecurrenceSet(patterns)) {
                return applyVectorRecurrencePatterns(node, patterns);
            }

            List<NaturalArray> targetArrays = new ArrayList<NaturalArray>();
            List<List<PatternResult>> groupedPatterns = new ArrayList<List<PatternResult>>();
            Map<Integer, Integer> arrayIdToGroupIndex = new HashMap<Integer, Integer>();

            for (PatternResult result : patterns) {
                if (result == null || result.targetArray == null) continue;

                Object resolvedArray = dispatcher.dispatch(result.targetArray);
                resolvedArray = typeSystem.unwrap(resolvedArray);

                if (!(resolvedArray instanceof NaturalArray)) {
                    DebugSystem.debug("OPTIMIZER", "Array not optimizable, falling back to normal execution");
                    return arrayHandler.executeForLoopNormally(node);
                }

                NaturalArray naturalArray = (NaturalArray) resolvedArray;
                int arrayId = naturalArray.getArrayId();
                Integer existingGroup = arrayIdToGroupIndex.get(arrayId);
                int groupIndex = existingGroup != null ? existingGroup : -1;

                if (groupIndex == -1) {
                    targetArrays.add(naturalArray);
                    List<PatternResult> newGroup = new ArrayList<PatternResult>();
                    newGroup.add(result);
                    groupedPatterns.add(newGroup);
                    arrayIdToGroupIndex.put(arrayId, targetArrays.size() - 1);
                } else {
                    groupedPatterns.get(groupIndex).add(result);
                }
            }

            if (targetArrays.isEmpty()) {
                DebugSystem.debug("OPTIMIZER", "No target arrays found, falling back to normal execution");
                return arrayHandler.executeForLoopNormally(node);
            }

            long start = 0, end = 0;
            boolean boundsFound = false;

            if (node.range != null) {
                Object startObj = dispatcher.dispatch(node.range.start);
                Object endObj = dispatcher.dispatch(node.range.end);
                start = exprHandler.toLong(startObj);
                end = exprHandler.toLong(endObj);
                boundsFound = true;
            } else if (node.arraySource != null) {
                Object sourceObj = dispatcher.dispatch(node.arraySource);
                if (sourceObj instanceof NaturalArray) {
                    NaturalArray sourceArr = (NaturalArray) sourceObj;
                    if (sourceArr.size() > 0) {
                        start = 0;
                        end = sourceArr.size() - 1;
                        boundsFound = true;
                    }
                }
            }

            if (!boundsFound) {
                DebugSystem.debug("OPTIMIZER", "Could not determine bounds, falling back to normal execution");
                return arrayHandler.executeForLoopNormally(node);
            }

            long min = Math.min(start, end);
            long max = Math.max(start, end);

            for (int arrayIndex = 0; arrayIndex < targetArrays.size(); arrayIndex++) {
                NaturalArray arr = targetArrays.get(arrayIndex);
                List<PatternResult> arrayPatterns = groupedPatterns.get(arrayIndex);

                for (PatternResult result : arrayPatterns) {
                    if (result.type == PatternType.SEQUENCE) {
                        applySequencePattern(arr, (SequencePattern.Pattern) result.pattern, min, max, node.iterator);
                    } else if (result.type == PatternType.CONDITIONAL) {
                        applyConditionalPattern(arr, (ConditionalPattern) result.pattern, min, max, node.iterator);
                    } else if (result.type == PatternType.ARRAY_RECURRENCE) {
                        applyArrayRecurrencePattern(arr, (AccumulationPattern) result.pattern, min, max, node.iterator);
                    }
                }
            }

            return targetArrays.get(targetArrays.size() - 1);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Pattern application failed, falling back to normal execution", e);
        }
    }

    public void applyConditionalPattern(NaturalArray arr, ConditionalPattern pattern,
                                        long min, long max, String iterator) {
        if (pattern == null) throw new InternalError("applyConditionalPattern called with null pattern");
        if (arr == null) throw new InternalError("applyConditionalPattern called with null array");

        try {
            List<Expr> conditions = new ArrayList<Expr>();
            List<List<Stmt>> branchStatements = new ArrayList<List<Stmt>>();

            for (ConditionalPattern.Branch branch : pattern.branches) {
                conditions.add(branch.condition);
                branchStatements.add(branch.statements);
            }

            ConditionalFormula formula = new ConditionalFormula(
                min, max, iterator,
                conditions,
                branchStatements,
                pattern.elseStatements
            );
            arr.addConditionalFormula(formula);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Failed to apply conditional pattern", e);
        }
    }

    public void applySequencePattern(NaturalArray arr, SequencePattern.Pattern pattern,
                                     long min, long max, String iterator) {
        if (pattern == null) throw new InternalError("applySequencePattern called with null pattern");
        if (arr == null) throw new InternalError("applySequencePattern called with null array");

        try {
            SequenceFormula formula;

            if (pattern.isSimple()) {
                formula = SequenceFormula.createSimple(min, max, pattern.getFinalExpression(), iterator);
            } else {
                formula = SequenceFormula.createFromSequence(
                    min, max, iterator,
                    pattern.getTempVarNames(),
                    pattern.getTempExpressions(),
                    pattern.getFinalExpression()
                );
            }

            arr.addSequenceFormula(formula);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Failed to apply sequence pattern", e);
        }
    }

    public void applyArrayRecurrencePattern(NaturalArray arr, AccumulationPattern pattern,
                                            long min, long max, String iterator) {
// TBA
    }

    private boolean isVectorRecurrenceSet(List<PatternResult> patterns) {
        if (patterns == null || patterns.isEmpty()) return false;
        for (PatternResult result : patterns) {
            if (result == null || result.type != PatternType.VECTOR_RECURRENCE) {
                return false;
            }
            if (!(result.pattern instanceof AccumulationPattern)) {
                return false;
            }
            AccumulationPattern acc = (AccumulationPattern) result.pattern;
            if (acc.type != AccumulationPattern.AccumulationType.VEC) {
                return false;
            }
        }
        return true;
    }

    private Object applyVectorRecurrencePatterns(For node, List<PatternResult> patterns) {
        PatternResult first = patterns.get(0);
        AccumulationPattern pattern = (AccumulationPattern) first.pattern;

        long start = 0L, end = 0L;
        boolean boundsFound = false;

        if (node.range != null) {
            Object startObj = dispatcher.dispatch(node.range.start);
            Object endObj = dispatcher.dispatch(node.range.end);
            start = exprHandler.toLong(startObj);
            end = exprHandler.toLong(endObj);
            boundsFound = true;
        } else if (node.arraySource != null) {
            Object sourceObj = dispatcher.dispatch(node.arraySource);
            sourceObj = typeSystem.unwrap(sourceObj);
            if (sourceObj instanceof NaturalArray) {
                NaturalArray sourceArr = (NaturalArray) sourceObj;
                if (sourceArr.size() > 0) {
                    start = 0L;
                    end = sourceArr.size() - 1L;
                    boundsFound = true;
                }
            } else if (sourceObj instanceof List) {
                List<?> sourceList = (List<?>) sourceObj;
                if (!sourceList.isEmpty()) {
                    start = 0L;
                    end = sourceList.size() - 1L;
                    boundsFound = true;
                }
            }
        }

        if (!boundsFound) {
            DebugSystem.debug("OPTIMIZER", "Vector recurrence: unable to resolve loop bounds");
            return arrayHandler.executeForLoopNormally(node);
        }

        long min = Math.min(start, end);
        long max = Math.max(start, end);
        long iterations = max - min + 1;

        // Create AccumulationFormula for VEC
        AccumulationFormula vecFormula = new AccumulationFormula(
            pattern.vectorDim,
            pattern.vectorOrder,
            pattern.vectorCoeffs,
            pattern.vectorConstants,
            pattern.vectorSeedValues
        );
        
        AutoStackingNumber result = vecFormula.evaluate(AutoStackingNumber.fromLong(iterations));
        
        // Find and update the target array
        for (PatternResult resultPattern : patterns) {
            Object resolved = dispatcher.dispatch(resultPattern.targetArray);
            resolved = typeSystem.unwrap(resolved);
            if (resolved instanceof NaturalArray) {
                ((NaturalArray) resolved).set(0, result);
                break;
            }
        }
        
        return result;
    }
}