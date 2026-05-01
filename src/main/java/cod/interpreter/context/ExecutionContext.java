package cod.interpreter.context;

import cod.ast.node.Type;
import cod.error.InternalError;
import cod.interpreter.handler.TypeHandler;
import java.util.*;

public class ExecutionContext {
    
    public ObjectInstance objectInstance;
    public Type currentClass;
    public String currentMethodName;
    public LambdaClosure currentLambdaClosure;
    
    // Locals with scope stacking
    private List<Map<String, Object>> localsStack;
    private List<Map<String, String>> localTypesStack;
    
    // O(1) Flattened lookup cache for Lexical Addressing speed
    private Map<String, Object> flattenedLocalsCache = new HashMap<String, Object>();
    private boolean flattenedDirty = true;
    
    // Slot values (method return slots) - OPTIMIZED
    private Map<String, Object> slotValues;
    private Map<String, String> slotTypes;
    public Set<String> slotsInCurrentPath;
    
    // Slot access caches for O(1) lookups
    private Map<String, Integer> slotIndexMap;
    private List<String> slotNamesList;
    private List<Object> slotValuesList;
    private List<String> slotTypesList;
    
    // Fast path flags
    private boolean hasSlots = false;
    private boolean slotsOptimized = false;
    
    // ========== THREAD LOCAL CONTEXT ==========
    private static final ThreadLocal<ExecutionContext> currentContext = new ThreadLocal<ExecutionContext>();
    private static final ThreadLocal<Integer> unsafeCommitDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    
    // ========== OPTIMIZED LOOP CONTEXT ==========
    private boolean inOptimizedLoop = false;
    private List<Object> pendingOutputs = new ArrayList<Object>();
    private boolean unsafeExecutionContext = false;
    private IdentityHashMap<Object, Map<Long, Integer>> activeBorrowsByContainer;
    
    // ========== TYPE HANDLER ==========
    private final TypeHandler typeHandler;
    
    /**
     * Set the current context for this thread
     */
    public static void setCurrentContext(ExecutionContext ctx) {
        currentContext.set(ctx);
    }
    
    /**
     * Get the current context for this thread
     */
    public static ExecutionContext getCurrentContext() {
        return currentContext.get();
    }
    
    /**
     * Get the base (root) scope map that contains all variables.
     * This is the same map that was passed in from REPLRunner.
     */
    public Map<String, Object> getLocalsMap() {
        if (localsStack == null || localsStack.isEmpty()) {
            return new HashMap<String, Object>();
        }
        // Return the root scope (index 0) which contains all variables
        return localsStack.get(0);
    }
    
    /**
     * Clear the current context for this thread
     */
    public static void clearCurrentContext() {
        currentContext.remove();
    }

    public static void enterUnsafeCommitAllowance() {
        unsafeCommitDepth.set(unsafeCommitDepth.get() + 1);
    }

    public static void exitUnsafeCommitAllowance() {
        int current = unsafeCommitDepth.get();
        unsafeCommitDepth.set(current > 0 ? current - 1 : 0);
    }

    public static boolean isUnsafeCommitAllowed() {
        return unsafeCommitDepth.get() > 0;
    }
    
    /**
     * Mark that we're entering an optimized loop
     */
    public void enterOptimizedLoop() {
        this.inOptimizedLoop = true;
        this.pendingOutputs.clear();
    }
    
    /**
     * Mark that we're exiting an optimized loop
     */
    public void exitOptimizedLoop() {
        this.inOptimizedLoop = false;
        this.pendingOutputs.clear();
    }
    
    /**
     * Check if we're in an optimized loop
     */
    public boolean isInOptimizedLoop() {
        return inOptimizedLoop;
    }
    
    /**
     * Record an output for later playback
     */
    public void recordOptimizedOutput(Object value) {
        if (inOptimizedLoop) {
            pendingOutputs.add(value);
        }
    }
    
    /**
     * Get and clear pending outputs
     */
    public List<Object> flushPendingOutputs() {
        List<Object> outputs = new ArrayList<Object>(pendingOutputs);
        pendingOutputs.clear();
        return outputs;
    }

    public boolean isUnsafeExecutionContext() {
        return unsafeExecutionContext;
    }

    public void setUnsafeExecutionContext(boolean unsafeExecutionContext) {
        this.unsafeExecutionContext = unsafeExecutionContext;
    }
    
    public ExecutionContext(ObjectInstance obj, Map<String, Object> locals, 
                           Map<String, Object> slotValues, Map<String, String> slotTypes,
                           TypeHandler typeHandler) {
        if (typeHandler == null) {
            throw new InternalError("ExecutionContext constructed with null typeHandler");
        }
        
        this.objectInstance = obj;
        this.currentClass = null;
        this.currentMethodName = null;
        this.currentLambdaClosure = null;
        this.typeHandler = typeHandler;
        this.activeBorrowsByContainer = new IdentityHashMap<Object, Map<Long, Integer>>();
        
        // Initialize locals
        this.localsStack = new ArrayList<Map<String, Object>>();
        this.localTypesStack = new ArrayList<Map<String, String>>();
        
        Map<String, Object> initialLocals = new HashMap<String, Object>();
        if (locals != null) {
            initialLocals.putAll(locals);
        }
        this.localsStack.add(initialLocals);
        
        Map<String, String> initialTypes = new HashMap<String, String>();
        this.localTypesStack.add(initialTypes);
        
        // OPTIMIZED: Initialize slots with parallel arrays for O(1) access
        this.slotValues = slotValues != null ? slotValues : new HashMap<String, Object>();
        this.slotTypes = slotTypes != null ? slotTypes : new HashMap<String, String>();
        this.slotsInCurrentPath = new HashSet<String>();
        
        // Build optimized slot access structures
        optimizeSlotAccess();
        registerInitialBorrowState(initialLocals);
        this.flattenedDirty = true;
    }
    
    /**
     * Get the type handler
     */
    public TypeHandler getTypeHandler() {
        return typeHandler;
    }
    
    // Build parallel arrays for O(1) slot access
    private void optimizeSlotAccess() {
        if (slotValues == null || slotValues.isEmpty()) {
            this.hasSlots = false;
            this.slotsOptimized = false;
            return;
        }
        
        int size = slotValues.size();
        this.slotIndexMap = new HashMap<String, Integer>(size);
        this.slotNamesList = new ArrayList<String>(size);
        this.slotValuesList = new ArrayList<Object>(size);
        this.slotTypesList = new ArrayList<String>(size);
        
        int index = 0;
        for (Map.Entry<String, Object> entry : slotValues.entrySet()) {
            String name = entry.getKey();
            slotIndexMap.put(name, index);
            slotNamesList.add(name);
            slotValuesList.add(entry.getValue());
            slotTypesList.add(slotTypes.get(name));
            index++;
        }
        
        this.hasSlots = true;
        this.slotsOptimized = true;
    }
    
    // O(1) slot value get by name
    public Object getSlotValue(String slotName) {
        if (slotName == null) {
            throw new InternalError("getSlotValue called with null slotName");
        }
        
        // Fast path - use index map
        if (slotsOptimized && slotIndexMap.containsKey(slotName)) {
            int index = slotIndexMap.get(slotName);
            return slotValuesList.get(index);
        }
        
        // Fallback to map
        return slotValues.get(slotName);
    }
    
    // O(1) slot value set by name
    public void setSlotValue(String slotName, Object value) {
        if (slotName == null) {
            throw new InternalError("setSlotValue called with null slotName");
        }
        
        // Update both representations
        Object previous = slotValues.put(slotName, value);
        replaceTrackedValue(previous, value);
        
        if (slotsOptimized && slotIndexMap.containsKey(slotName)) {
            int index = slotIndexMap.get(slotName);
            slotValuesList.set(index, value);
        }
    }
    
    // O(1) slot type get by name
    public String getSlotType(String slotName) {
        if (slotName == null) {
            throw new InternalError("getSlotType called with null slotName");
        }
        
        // Fast path - use index map
        if (slotsOptimized && slotIndexMap.containsKey(slotName)) {
            int index = slotIndexMap.get(slotName);
            return slotTypesList.get(index);
        }
        
        // Fallback to map
        return slotTypes.get(slotName);
    }
    
    // O(1) slot exists check
    public boolean hasSlot(String slotName) {
        if (slotName == null) return false;
        
        if (slotsOptimized) {
            return slotIndexMap.containsKey(slotName);
        }
        return slotValues.containsKey(slotName);
    }
    
    // Get slot by index (for iteration)
    public String getSlotName(int index) {
        if (!slotsOptimized || index < 0 || index >= slotNamesList.size()) {
            return null;
        }
        return slotNamesList.get(index);
    }
    
    // Get slot count
    public int getSlotCount() {
        if (slotsOptimized) {
            return slotNamesList.size();
        }
        return slotValues != null ? slotValues.size() : 0;
    }
    
    // Mark slot as assigned in current path
    public void markSlotAssigned(String slotName) {
        if (slotName == null) return;
        slotsInCurrentPath.add(slotName);
    }
    
    // Check if all slots are assigned
    public boolean allSlotsAssigned() {
        if (!hasSlots) return false;
        if (slotsInCurrentPath.isEmpty()) return false;
        
        // Fast path - use size check
        if (slotsOptimized) {
            return slotsInCurrentPath.size() >= slotNamesList.size();
        }
        
        // Fallback
        for (String slotName : slotValues.keySet()) {
            if (!slotsInCurrentPath.contains(slotName)) {
                return false;
            }
        }
        return true;
    }
    
    public Map<String, Object> getSlotValues() {
        return slotValues;
    }
    
    public Map<String, String> getSlotTypes() {
        return slotTypes;
    }
    
    public void pushScope() {
        localsStack.add(new HashMap<String, Object>());
        localTypesStack.add(new HashMap<String, String>());
        flattenedDirty = true;
    }
    
    public void popScope() {
        if (localsStack.size() > 1) {
            Map<String, Object> removedScope = localsStack.remove(localsStack.size() - 1);
            for (Object value : removedScope.values()) {
                unregisterBorrowsFromValue(value);
            }
            localTypesStack.remove(localTypesStack.size() - 1);
            flattenedDirty = true;
        }
    }
    
    public int getScopeDepth() {
        return localsStack.size();
    }
    
    public Map<String, Object> getScope(int depth) {
        if (depth < 0 || depth >= localsStack.size()) {
            return null;
        }
        return localsStack.get(depth);
    }
    
    public List<Map<String, Object>> getLocalsStack() {
        return localsStack;
    }
    
    public List<Map<String, String>> getLocalTypesStack() {
        return localTypesStack;
    }

    // ========== OPTIMIZED VARIABLE LOOKUP ==========
    public Object getVariable(String name) {
        if (name == null) return null;
        
        if (flattenedDirty) {
            refreshFlattenedCache();
        }
        return flattenedLocalsCache.get(name);
    }
    
    private void refreshFlattenedCache() {
        flattenedLocalsCache.clear();
        // Traverse from bottom to top so that inner scopes correctly overwrite outer ones
        for (Map<String, Object> scope : localsStack) {
            flattenedLocalsCache.putAll(scope);
        }
        flattenedDirty = false;
    }
    
    public void setVariable(String name, Object value) {
        if (name == null) {
            throw new InternalError("setVariable called with null name");
        }
        
        // Check if variable exists in any scope
        for (int i = localsStack.size() - 1; i >= 0; i--) {
            Map<String, Object> scope = localsStack.get(i);
            if (scope.containsKey(name)) {
                Object previous = scope.put(name, value);
                replaceTrackedValue(previous, value);
                flattenedDirty = true;
                return;
            }
        }
        
        // Create in current scope
        Map<String, Object> currentScope = localsStack.get(localsStack.size() - 1);
        Object previous = currentScope.put(name, value);
        replaceTrackedValue(previous, value);
        flattenedDirty = true;
    }

    public int resolveVariableScopeIndex(String name) {
        if (name == null) return -1;
        for (int i = localsStack.size() - 1; i >= 0; i--) {
            Map<String, Object> scope = localsStack.get(i);
            if (scope.containsKey(name)) {
                return i;
            }
        }
        return localsStack.size() - 1;
    }

    public int resolveVariableTypeScopeIndex(String name) {
        if (name == null) return -1;
        for (int i = localTypesStack.size() - 1; i >= 0; i--) {
            Map<String, String> scope = localTypesStack.get(i);
            if (scope.containsKey(name)) {
                return i;
            }
        }
        return localTypesStack.size() - 1;
    }

    public String getVariableTypeAtScope(int scopeIndex, String name) {
        if (name == null || scopeIndex < 0 || scopeIndex >= localTypesStack.size()) {
            return null;
        }
        return localTypesStack.get(scopeIndex).get(name);
    }

    public void setVariableAtScope(int scopeIndex, String name, Object value) {
        if (name == null) {
            throw new InternalError("setVariableAtScope called with null name");
        }
        if (scopeIndex < 0 || scopeIndex >= localsStack.size()) {
            setVariable(name, value);
            return;
        }
        Map<String, Object> scope = localsStack.get(scopeIndex);
        Object previous = scope.put(name, value);
        replaceTrackedValue(previous, value);
        flattenedDirty = true;
    }

    public void setVariableTypeAtScope(int scopeIndex, String name, String type) {
        if (name == null) {
            throw new InternalError("setVariableTypeAtScope called with null name");
        }
        if (scopeIndex < 0 || scopeIndex >= localTypesStack.size()) {
            setVariableType(name, type);
            return;
        }
        localTypesStack.get(scopeIndex).put(name, type);
    }
    
    public String getVariableType(String name) {
        if (name == null) return null;
        
        for (int i = localTypesStack.size() - 1; i >= 0; i--) {
            Map<String, String> scope = localTypesStack.get(i);
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }
    
    public void setVariableType(String name, String type) {
        if (name == null) {
            throw new InternalError("setVariableType called with null name");
        }
        
        Map<String, String> currentScope = localTypesStack.get(localTypesStack.size() - 1);
        currentScope.put(name, type);
    }
    
    public Map<String, Object> locals() {
        Map<String, Object> all = new HashMap<String, Object>();
        for (Map<String, Object> scope : localsStack) {
            all.putAll(scope);
        }
        return all;
    }

    /**
     * Remove a variable from the current scope
     * Returns the removed value, or null if not found
     */
    public Object removeVariable(String name) {
        if (name == null) return null;
        
        // Check from innermost to outermost scope
        for (int i = localsStack.size() - 1; i >= 0; i--) {
            Map<String, Object> scope = localsStack.get(i);
            if (scope.containsKey(name)) {
                Object removed = scope.remove(name);
                unregisterBorrowsFromValue(removed);
                flattenedDirty = true;
                return removed;
            }
        }
        return null;
    }

    /**
     * Remove a variable from all scopes (for cleanup)
     * Returns true if found and removed
     */
    public boolean removeVariableFromAllScopes(String name) {
        if (name == null) return false;
        
        boolean found = false;
        for (int i = localsStack.size() - 1; i >= 0; i--) {
            Map<String, Object> scope = localsStack.get(i);
            if (scope.containsKey(name)) {
                Object removed = scope.remove(name);
                unregisterBorrowsFromValue(removed);
                found = true;
            }
        }
        if (found) flattenedDirty = true;
        return found;
    }
    
    public ExecutionContext copyWithVariable(String name, Object value, String type) {
        Map<String, Object> newLocals = locals();
        newLocals.put(name, value);
        
        Map<String, String> newTypes = new HashMap<String, String>();
        for (Map<String, String> scope : localTypesStack) {
            newTypes.putAll(scope);
        }
        if (type != null) {
            newTypes.put(name, type);
        }
        
        return new ExecutionContext(objectInstance, newLocals, slotValues, slotTypes, typeHandler);
    }

    public void setObjectField(String fieldName, Object value) {
        if (fieldName == null) {
            throw new InternalError("setObjectField called with null fieldName");
        }
        if (objectInstance == null || objectInstance.fields == null) {
            throw new InternalError("setObjectField called outside object context");
        }
        Object previous = objectInstance.fields.put(fieldName, value);
        replaceTrackedValue(previous, value);
    }

    public boolean hasActiveBorrow(Object container, long index) {
        if (container == null) return false;
        Map<Long, Integer> countsByIndex = activeBorrowsByContainer.get(container);
        if (countsByIndex == null) return false;
        Integer count = countsByIndex.get(index);
        return count != null && count > 0;
    }

    public void trackValueReplacement(Object oldValue, Object newValue) {
        replaceTrackedValue(oldValue, newValue);
    }
    
    // Clear slot optimization (if slots change)
    public void rebuildSlotOptimization() {
        this.slotsOptimized = false;
        this.slotIndexMap = null;
        this.slotNamesList = null;
        this.slotValuesList = null;
        this.slotTypesList = null;
        optimizeSlotAccess();
    }

    private void registerInitialBorrowState(Map<String, Object> initialLocals) {
        if (initialLocals != null) {
            for (Object value : initialLocals.values()) {
                registerBorrowsFromValue(value);
            }
        }
        if (slotValues != null) {
            for (Object value : slotValues.values()) {
                registerBorrowsFromValue(value);
            }
        }
        if (objectInstance != null && objectInstance.fields != null) {
            for (Object value : objectInstance.fields.values()) {
                registerBorrowsFromValue(value);
            }
        }
    }

    private void replaceTrackedValue(Object oldValue, Object newValue) {
        unregisterBorrowsFromValue(oldValue);
        registerBorrowsFromValue(newValue);
    }

    private void registerBorrowsFromValue(Object value) {
        collectBorrowsRecursive(value, 1);
    }

    private void unregisterBorrowsFromValue(Object value) {
        collectBorrowsRecursive(value, -1);
    }

    @SuppressWarnings("unchecked")
    private void collectBorrowsRecursive(Object value, int delta) {
        if (value == null) return;
        Object unwrapped = typeHandler.unwrap(value);
        if (unwrapped == null) return;

        if (unwrapped instanceof TypeHandler.PointerValue) {
            TypeHandler.PointerValue pointer = (TypeHandler.PointerValue) unwrapped;
            updateBorrowCount(pointer.container, pointer.index, delta);
            return;
        }

        if (unwrapped instanceof List) {
            for (Object element : (List<Object>) unwrapped) {
                collectBorrowsRecursive(element, delta);
            }
        }
    }

    private void updateBorrowCount(Object container, long index, int delta) {
        if (container == null || delta == 0) return;

        Map<Long, Integer> countsByIndex = activeBorrowsByContainer.get(container);
        if (countsByIndex == null) {
            if (delta < 0) return;
            countsByIndex = new HashMap<Long, Integer>();
            activeBorrowsByContainer.put(container, countsByIndex);
        }

        Integer current = countsByIndex.get(index);
        int next = (current != null ? current : 0) + delta;
        if (next > 0) {
            countsByIndex.put(index, next);
        } else {
            countsByIndex.remove(index);
            if (countsByIndex.isEmpty()) {
                activeBorrowsByContainer.remove(container);
            }
        }
    }
}
