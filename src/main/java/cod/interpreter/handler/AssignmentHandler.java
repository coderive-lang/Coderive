package cod.interpreter.handler;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.range.NaturalArray;
import cod.range.RangeObjects;
import cod.interpreter.context.ExecutionContext;
import cod.interpreter.*;
import cod.semantic.ConstructorResolver;
import cod.semantic.NamingValidator;

import java.util.*;

public class AssignmentHandler {
    private static final String BORROW_MUTATION_VIOLATION =
        "Borrow checker violation: cannot mutate index %d while it is currently borrowed by an active pointer";

    private final TypeHandler typeSystem;
    private final Interpreter interpreter;
    private final ExpressionHandler exprHandler;
    private final InterpreterVisitor dispatcher;
    
    public AssignmentHandler(TypeHandler typeSystem, Interpreter interpreter, 
                           ExpressionHandler exprHandler, InterpreterVisitor dispatcher) {
        if (typeSystem == null) {
            throw new InternalError("AssignmentHandler constructed with null typeSystem");
        }
        if (interpreter == null) {
            throw new InternalError("AssignmentHandler constructed with null interpreter");
        }
        if (exprHandler == null) {
            throw new InternalError("AssignmentHandler constructed with null exprHandler");
        }
        if (dispatcher == null) {
            throw new InternalError("AssignmentHandler constructed with null dispatcher");
        }
        
        this.typeSystem = typeSystem;
        this.interpreter = interpreter;
        this.exprHandler = exprHandler;
        this.dispatcher = dispatcher;
    }
    
    // === Core Assignment Logic ===
    
    public Object handleAssignment(Assignment node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleAssignment called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleAssignment called with null context");
        }
        
        try {
            Object newValue = dispatcher.dispatch(node.right);
            
            if (node.left instanceof IndexAccess) {
                return handleIndexAssignment((IndexAccess) node.left, newValue, ctx);
            } else if (node.left instanceof Expr) {
                return handleVariableAssignment((Expr) node.left, newValue, ctx);
            }
            
            throw new ProgramError("Invalid assignment target");
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Assignment failed", e);
        }
    }
    
    // In AssignmentHandler.java - optimized slot handling

public Object handleSlotAssignment(SlotAssignment node, ExecutionContext ctx) {
    if (node == null) {
        throw new InternalError("handleSlotAssignment called with null node");
    }
    if (ctx == null) {
        throw new InternalError("handleSlotAssignment called with null context");
    }
    
    try {
        Object value = dispatcher.dispatch(node.value);
        String varName = node.slotName;
        
        if (varName != null && !varName.isEmpty() && !"_".equals(varName)) {
            return assignToSlot(varName, value, ctx);
        }
        // Implicit slot - use first available slot
        if (ctx.getSlotCount() > 0) {
            String slotTarget = ctx.getSlotName(0); // O(1) with new optimization
            return assignToSlot(slotTarget, value, ctx);
        }
        return handleRegularAssignment(varName, value, ctx);
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Slot assignment failed", e);
    }
}

public Object handleMultipleSlotAssignment(MultipleSlotAssignment node, ExecutionContext ctx) {
    if (node == null) {
        throw new InternalError("handleMultipleSlotAssignment called with null node");
    }
    if (ctx == null) {
        throw new InternalError("handleMultipleSlotAssignment called with null context");
    }
    
    try {
        Object lastValue = null;
        int slotIndex = 0;
        
        for (SlotAssignment assign : node.assignments) {
            Object value = dispatcher.dispatch(assign.value);
            String target = determineTargetOptimized(assign, ctx, slotIndex);
            
            assignToSlot(target, value, ctx);
            lastValue = value;
            slotIndex++;
        }
        return lastValue;
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Multiple slot assignment failed", e);
    }
}

// NEW: O(1) target determination
private String determineTargetOptimized(SlotAssignment assign, ExecutionContext ctx, int slotIndex) {
    String varName = assign.slotName;
    
    if (varName != null && !varName.isEmpty() && !"_".equals(varName)) {
        return varName;
    }
    if (slotIndex < ctx.getSlotCount()) {
        return ctx.getSlotName(slotIndex); // O(1)
    }
    throw new ProgramError("Too many positional slot assignments. Expected at most " + 
        ctx.getSlotCount() + ", got " + (slotIndex + 1));
}

// NEW: O(1) slot assignment
private Object assignToSlot(String slotTarget, Object value, ExecutionContext ctx) {
    try {
        if (ctx.hasSlot(slotTarget)) { // O(1)
            String declaredType = ctx.getSlotType(slotTarget); // O(1)
            validateAssignmentType(declaredType, value, slotTarget);
            value = typeSystem.normalizeForDeclaredType(declaredType, value);
            if (declaredType != null && declaredType.indexOf('|') >= 0) {
                value = typeSystem.wrapUnionType(value, declaredType);
            }
            
            ctx.setSlotValue(slotTarget, value); // O(1)
            ctx.markSlotAssigned(slotTarget); // O(1)
            return value;
        }
        throw new ProgramError("Assignment to slot '" + slotTarget + "' failed: slot not declared");
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Slot assignment failed for: " + slotTarget, e);
    }
}
    
    // === Assignment Implementation Methods ===
    
    @SuppressWarnings("unchecked")
    private Object handleIndexAssignment(IndexAccess indexAccess, Object newValue, ExecutionContext ctx) {
        try {
            Object arrayObj = dispatcher.dispatch(indexAccess.array);
            arrayObj = typeSystem.unwrap(arrayObj);
            Object indexObj = dispatcher.dispatch(indexAccess.index);
            indexObj = typeSystem.unwrap(indexObj);
            
            if (indexObj instanceof List) {
                return assignTupleIndex(arrayObj, (List<?>) indexObj, newValue);
            }
            
            if (RangeObjects.isRangeSpec(indexObj)) {
                return assignRange(arrayObj, indexObj, newValue);
            }
            
            if (RangeObjects.isMultiRangeSpec(indexObj)) {
                return assignMultiRange(arrayObj, indexObj, newValue);
            }
            
            if (arrayObj instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) arrayObj;
                long index = exprHandler.toLongIndex(indexObj);
                ensureNoActiveBorrow(arrayObj, index, ctx);
                Object previous = natural.peekMaterialized(index);
                natural.set(index, newValue);
                ctx.trackValueReplacement(previous, newValue);
                return newValue;
            }
            
            if (arrayObj instanceof List) {
                int intIndex = exprHandler.toIntIndex(indexObj);
                ensureNoActiveBorrow(arrayObj, intIndex, ctx);
                List<Object> list = (List<Object>) arrayObj;
                Object previous = list.set(intIndex, newValue);
                ctx.trackValueReplacement(previous, newValue);
                return newValue;
            }
            
            throw new ProgramError("Invalid array assignment target: " +
                (arrayObj != null ? arrayObj.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Index assignment failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object assignTupleIndex(Object arrayObj, List<?> tupleIndices, Object newValue) {
        if (tupleIndices == null || tupleIndices.isEmpty()) {
            throw new ProgramError("Invalid multidimensional assignment: empty index tuple");
        }
        
        Object current = arrayObj;
        for (int i = 0; i < tupleIndices.size() - 1; i++) {
            Object idxObj = typeSystem.unwrap(tupleIndices.get(i));
            if (RangeObjects.isRangeSpec(idxObj)) {
                current = extractRange(current, idxObj);
                continue;
            }
            if (RangeObjects.isMultiRangeSpec(idxObj)) {
                current = extractMultiRange(current, idxObj);
                continue;
            }
            if (current instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) current;
                long idx = exprHandler.toLongIndex(idxObj);
                current = natural.get(idx);
                continue;
            }
            if (current instanceof List) {
                List<Object> list = (List<Object>) current;
                int idx = exprHandler.toIntIndex(idxObj);
                if (idx < 0 || idx >= list.size()) {
                    throw new ProgramError("Index out of bounds: " + idx + " for array of size " + list.size());
                }
                current = list.get(idx);
                continue;
            }
            throw new ProgramError("Invalid multidimensional assignment path: expected NaturalArray or List, got "
                + (current != null ? current.getClass().getSimpleName() : "null"));
        }
        
        Object lastIdxObj = typeSystem.unwrap(tupleIndices.get(tupleIndices.size() - 1));
        if (RangeObjects.isRangeSpec(lastIdxObj)) {
            return assignRange(current, lastIdxObj, newValue);
        }
        if (RangeObjects.isMultiRangeSpec(lastIdxObj)) {
            return assignMultiRange(current, lastIdxObj, newValue);
        }
        if (current instanceof NaturalArray) {
            NaturalArray natural = (NaturalArray) current;
            long idx = exprHandler.toLongIndex(lastIdxObj);
            natural.set(idx, newValue);
            return newValue;
        }
        if (current instanceof List) {
            List<Object> list = (List<Object>) current;
            int idx = exprHandler.toIntIndex(lastIdxObj);
            list.set(idx, newValue);
            return newValue;
        }
        
        throw new ProgramError("Invalid array assignment target in multidimensional assignment: " +
            (current != null ? current.getClass().getSimpleName() : "null"));
    }

    @SuppressWarnings("unchecked")
    private Object extractRange(Object array, Object range) {
        if (array instanceof NaturalArray) {
            return ((NaturalArray) array).getRange(range);
        }
        if (array instanceof List) {
            List<Object> list = (List<Object>) array;
            List<Object> result = new ArrayList<Object>();
            long start = exprHandler.toLongIndex(RangeObjects.getStart(range));
            long end = exprHandler.toLongIndex(RangeObjects.getEnd(range));
            long step = exprHandler.calculateStep(range);
            start = normalizeListIndex(start, list.size());
            end = normalizeListIndex(end, list.size());
            
            if (start < 0 || start >= list.size()) {
                throw new ProgramError("Range start index out of bounds: " + start + " for array of size " + list.size());
            }
            if (end < 0 || end >= list.size()) {
                throw new ProgramError("Range end index out of bounds: " + end + " for array of size " + list.size());
            }
            if (step > 0) {
                for (long i = start; i <= end && i < list.size(); i += step) {
                    result.add(list.get((int) i));
                }
            } else if (step < 0) {
                for (long i = start; i >= end && i >= 0; i += step) {
                    result.add(list.get((int) i));
                }
            } else {
                throw new ProgramError("Range step cannot be zero");
            }
            return result;
        }
        throw new ProgramError("Cannot apply range index during multidimensional assignment to " +
            (array != null ? array.getClass().getSimpleName() : "null"));
    }

    @SuppressWarnings("unchecked")
    private Object extractMultiRange(Object array, Object multiRange) {
        if (array instanceof NaturalArray) {
            return ((NaturalArray) array).getMultiRange(multiRange);
        }
        if (array instanceof List) {
            List<Object> list = (List<Object>) array;
            List<Object> result = new ArrayList<Object>();
            for (Object range : RangeObjects.getRanges(multiRange)) {
                Object sub = extractRange(list, range);
                if (sub instanceof List) {
                    result.addAll((List<Object>) sub);
                }
            }
            return result;
        }
        throw new ProgramError("Cannot apply multi-range index during multidimensional assignment to " +
            (array != null ? array.getClass().getSimpleName() : "null"));
    }
    
    private long normalizeListIndex(long index, int size) {
        if (index < 0) {
            return size + index;
        }
        return index;
    }

    private void ensureNoActiveBorrow(Object container, long index, ExecutionContext ctx) {
        if (!isBorrowCheckerActive(ctx)) {
            return;
        }
        if (ctx.hasActiveBorrow(container, index)) {
            throwBorrowMutationViolation(index);
        }
    }

    private void throwBorrowMutationViolation(long index) {
        throw new ProgramError(String.format(BORROW_MUTATION_VIOLATION, index));
    }

    private boolean isBorrowCheckerActive(ExecutionContext ctx) {
        return ctx != null && (ctx.isUnsafeExecutionContext() || (ctx.currentClass != null && ctx.currentClass.isUnsafe));
    }

    private Object handleVariableAssignment(Expr target, Object newValue, ExecutionContext ctx) {
        try {
            if (target instanceof PropertyAccess) {
                PropertyAccess prop = (PropertyAccess) target;
                
                if (prop.left instanceof This) {
                    if (prop.right instanceof Identifier) {
                        Identifier right = (Identifier) prop.right;
                        return assignToField(right.name, newValue, ctx);
                    }
                } else if (prop.left instanceof Super) {
                    if (prop.right instanceof Identifier) {
                        Identifier right = (Identifier) prop.right;
                        return assignToSuperField(right.name, newValue, ctx);
                    }
                }
                
                throw new ProgramError("Invalid property assignment target");
            }
            
            if (target instanceof Identifier) {
                Identifier id = (Identifier) target;
                return assignToVariableScoped(id.name, newValue, ctx);
            }
            
            throw new ProgramError("Invalid assignment target: " + 
                (target != null ? target.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Variable assignment failed", e);
        }
    }
    
    private Object handleRegularAssignment(String varName, Object value, ExecutionContext ctx) {
        if (varName == null || "_".equals(varName)) {
            throw new ProgramError("Invalid assignment: cannot assign to '_'");
        }
        
        return assignToVariableScoped(varName, value, ctx);
    }
    
public Object assignToVariableScoped(String varName, Object newValue, ExecutionContext ctx) {
    // First check all scopes for existing variable
    for (int i = ctx.getScopeDepth() - 1; i >= 0; i--) {
        Map<String, Object> scope = ctx.getLocalsStack().get(i);
        if (scope.containsKey(varName)) {
            if (NamingValidator.isAllCaps(varName)) {
                throw new ProgramError("Cannot reassign constant '" + varName + "'");
            }
            return updateVariableInScope(varName, newValue, scope, i, ctx);
        }
    }
    
    // Then check object fields
    if (ctx.objectInstance != null && ctx.objectInstance.type != null) {
        Object fieldValue = interpreter.getConstructorResolver()
            .getFieldFromHierarchy(ctx.objectInstance.type, varName, ctx);
        if (fieldValue != null) {
            if (NamingValidator.isAllCaps(varName)) {
                throw new ProgramError("Cannot reassign constant field '" + varName + "'");
            }
            ctx.setObjectField(varName, newValue);
            return newValue;
        }
    }
    
    // If not found anywhere, this is an error - variable must be declared with := first
    throw new ProgramError("Variable '" + varName + "' not declared. Use ':=' for declaration, or declare it first.");
}
    
    private Object updateVariableInScope(String varName, Object newValue, 
                                       Map<String, Object> scope, int scopeIndex, 
                                       ExecutionContext ctx) {
        try {
            String declaredType = null;
            if (scopeIndex < ctx.getLocalTypesStack().size()) {
                Map<String, String> typeScope = ctx.getLocalTypesStack().get(scopeIndex);
                declaredType = typeScope.get(varName);
            }
            
            if (declaredType != null) {
                validateAssignmentType(declaredType, newValue, varName);
                newValue = typeSystem.normalizeForDeclaredType(declaredType, newValue);
                if (declaredType.indexOf('|') >= 0) {
                    newValue = typeSystem.wrapUnionType(newValue, declaredType);
                }
            }
            
            ctx.setVariable(varName, newValue);
            return newValue;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Variable update failed for: " + varName, e);
        }
    }
    
    private Object assignToField(String fieldName, Object newValue, ExecutionContext ctx) {
        try {
            Object existingField = interpreter.getConstructorResolver()
                .getFieldFromHierarchy(ctx.objectInstance.type, fieldName, ctx);
            if (existingField != null) {
                if (NamingValidator.isAllCaps(fieldName)) {
                    throw new ProgramError("Cannot reassign constant field '" + fieldName + "'");
                }
                ctx.setObjectField(fieldName, newValue);
                return newValue;
            } else {
                throw new ProgramError("Cannot assign to undefined field via this: " + fieldName);
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Field assignment failed for: " + fieldName, e);
        }
    }
    
    private Object assignToSuperField(String fieldName, Object newValue, ExecutionContext ctx) {
        try {
            if (ctx.objectInstance == null || ctx.objectInstance.type == null) {
                throw new ProgramError("Cannot assign to 'super." + fieldName + "' outside of object context");
            }
            
            if (ctx.objectInstance.type.extendName == null) {
                throw new ProgramError("Cannot assign to 'super." + fieldName + "' - no parent class");
            }
            
            ConstructorResolver resolver = interpreter.getConstructorResolver();
            Type parentType = resolver.findParentType(ctx.objectInstance.type, ctx);
            
            if (parentType == null) {
                throw new ProgramError("Parent class not found for 'super." + fieldName + "'");
            }
            
            boolean fieldDeclared = isFieldDeclaredInTypeHierarchy(parentType, fieldName, ctx);
            if (fieldDeclared) {
                if (NamingValidator.isAllCaps(fieldName)) {
                    throw new ProgramError("Cannot reassign constant field '" + fieldName + "'");
                }
                ctx.setObjectField(fieldName, newValue);
                return newValue;
            }
            throw new ProgramError("Cannot assign to undefined field via super: " + fieldName);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Super field assignment failed for: " + fieldName, e);
        }
    }
    
    // === Helper Methods ===
private void validateAssignmentType(String declaredType, Object value, String name) {
    int expected = TypeHandler.parseTypeMask(declaredType);
    int actual = typeSystem.getConcreteMask(value);
    
    if ((actual & expected) == 0) {
        throw new ProgramError(
            "Type mismatch for " + name + 
            ". Expected " + declaredType + 
            ", got " + typeSystem.getConcreteType(value));
    }
}

    private boolean isFieldDeclaredInTypeHierarchy(Type type, String fieldName, ExecutionContext ctx) {
        Type current = type;
        ConstructorResolver resolver = interpreter.getConstructorResolver();
        while (current != null) {
            if (current.fields != null) {
                for (Field field : current.fields) {
                    if (field != null && field.name != null && field.name.equals(fieldName)) {
                        return true;
                    }
                }
            }
            current = resolver.findParentType(current, ctx);
        }
        return false;
    }
    
    // === Range/Multi-Range Assignment Methods ===
    
    @SuppressWarnings("unchecked")
    private Object assignRange(Object array, Object range, Object value) {
        try {
            if (array instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) array;
                natural.setRange(range, value);
                return value;
            } else if (array instanceof List) {
                List<Object> list = (List<Object>) array;
                setListRange(list, range, value);
                return value;
            }
            throw new ProgramError("Cannot assign range to " + 
                (array != null ? array.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Range assignment failed", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Object assignMultiRange(Object array, Object multiRange, Object value) {
        try {
            if (array instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) array;
                natural.setMultiRange(multiRange, value);
                return value;
            } else if (array instanceof List) {
                List<Object> list = (List<Object>) array;
                setListMultiRange(list, multiRange, value);
                return value;
            }
            throw new ProgramError("Cannot assign multi-range to " + 
                (array != null ? array.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Multi-range assignment failed", e);
        }
    }
    
    private void setListRange(List<Object> list, Object range, Object value) {
        try {
            long start = exprHandler.toLongIndex(RangeObjects.getStart(range));
            long end = exprHandler.toLongIndex(RangeObjects.getEnd(range));
            long step = exprHandler.calculateStep(range);
            
            if (start < 0) start = list.size() + start;
            if (end < 0) end = list.size() + end;
            
            if (step > 0) {
                for (long i = start; i <= end && i < list.size(); i += step) {
                    list.set((int) i, value);
                }
            } else if (step < 0) {
                for (long i = start; i >= end && i >= 0; i += step) {
                    list.set((int) i, value);
                }
            } else {
                throw new InternalError("Step cannot be zero - should have been caught earlier");
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("List range assignment failed", e);
        }
    }
    
    private void setListMultiRange(List<Object> list, Object multiRange, Object value) {
        try {
            for (Object range : RangeObjects.getRanges(multiRange)) {
                setListRange(list, range, value);
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("List multi-range assignment failed", e);
        }
    }
}
