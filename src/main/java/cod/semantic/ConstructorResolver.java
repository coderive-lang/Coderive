package cod.semantic;

import cod.ast.node.*;
import cod.ast.ASTFactory;
import cod.debug.DebugSystem;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.*;
import cod.interpreter.context.*;
import cod.interpreter.handler.*;
import static cod.lexer.TokenType.Keyword.*;

import java.util.*;

public class ConstructorResolver {
    private final TypeHandler typeSystem;
    private final Interpreter interpreter;
    private final ImportResolver importResolver;
    private final PolicyResolver policyResolver;
    
    // Flattened method tables for O(1) method lookup
    private Map<String, Map<String, Method>> flattenedMethodTables = 
        new HashMap<String, Map<String, Method>>();
    
    // Flattened field tables for O(1) field lookup
    private Map<String, Map<String, Field>> flattenedFieldTables = 
        new HashMap<String, Map<String, Field>>();
    
    // Type hierarchy cache
    private Map<String, List<Type>> inheritanceChainCache = 
        new HashMap<String, List<Type>>();
    
    // Constructor signature cache
    private Map<String, Map<String, Constructor>> constructorSignatureCache = 
        new HashMap<String, Map<String, Constructor>>();
    
    public ConstructorResolver(TypeHandler typeSystem, Interpreter interpreter) {
        if (typeSystem == null) {
            throw new InternalError("ConstructorResolver constructed with null typeSystem");
        }
        if (interpreter == null) {
            throw new InternalError("ConstructorResolver constructed with null interpreter");
        }
        
        this.typeSystem = typeSystem;
        this.interpreter = interpreter;
        this.importResolver = interpreter.getImportResolver();
        this.policyResolver = new PolicyResolver(this.importResolver);
    }
    
    // Build flattened method table for a type
    private void buildFlattenedMethodTable(Type type, ExecutionContext ctx) {
        String typeKey = type.name;
        if (flattenedMethodTables.containsKey(typeKey)) {
            return;
        }
        
        Map<String, Method> methodTable = new HashMap<String, Method>();
        Map<String, Field> fieldTable = new HashMap<String, Field>();
        
        List<Type> chain = getInheritanceChainCached(type, ctx);
        
        for (Type chainType : chain) {
            if (chainType.methods != null) {
                for (Method method : chainType.methods) {
                    methodTable.put(method.methodName, method);
                }
            }
            
            if (chainType.fields != null) {
                for (Field field : chainType.fields) {
                    fieldTable.put(field.name, field);
                }
            }
        }
        
        flattenedMethodTables.put(typeKey, methodTable);
        flattenedFieldTables.put(typeKey, fieldTable);
        
        DebugSystem.debug("CONSTRUCTOR", "Built flattened tables for " + typeKey + 
            ": methods=" + methodTable.size() + ", fields=" + fieldTable.size());
    }
    
    // Get inheritance chain with caching
    private List<Type> getInheritanceChainCached(Type type, ExecutionContext ctx) {
        String typeKey = type.name;
        if (inheritanceChainCache.containsKey(typeKey)) {
            return inheritanceChainCache.get(typeKey);
        }
        
        List<Type> chain = new ArrayList<Type>();
        
        if (type.extendName != null) {
            Type parent = findParentType(type, ctx);
            if (parent != null) {
                chain.addAll(getInheritanceChainCached(parent, ctx));
            }
        }
        
        chain.add(type);
        inheritanceChainCache.put(typeKey, Collections.unmodifiableList(chain));
        
        return chain;
    }
    
    // Get constructor signature key
    private String getConstructorSignatureKey(Constructor constructor) {
        if (constructor.parameters == null || constructor.parameters.isEmpty()) {
            return "()";
        }
        
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < constructor.parameters.size(); i++) {
            if (i > 0) sb.append(",");
            Param p = constructor.parameters.get(i);
            sb.append(p.type);
        }
        sb.append(")");
        return sb.toString();
    }
    
    // Build constructor signature cache
    private void buildConstructorSignatureCache(Type type) {
        String typeKey = type.name;
        if (constructorSignatureCache.containsKey(typeKey)) {
            return;
        }
        
        Map<String, Constructor> signatureMap = new HashMap<String, Constructor>();
        
        if (type.constructors != null) {
            for (Constructor constructor : type.constructors) {
                String sigKey = getConstructorSignatureKey(constructor);
                signatureMap.put(sigKey, constructor);
            }
        }
        
        if (!signatureMap.containsKey("()")) {
            Constructor defaultConstructor = createDefaultConstructor(type);
            signatureMap.put("()", defaultConstructor);
        }
        
        constructorSignatureCache.put(typeKey, signatureMap);
        
        DebugSystem.debug("CONSTRUCTOR", "Built constructor cache for " + typeKey + 
            ": signatures=" + signatureMap.size());
    }
    
    public ObjectInstance resolveAndCreate(ConstructorCall call, ExecutionContext ctx) {
        if (call == null) {
            throw new InternalError("resolveAndCreate called with null call");
        }
        if (ctx == null) {
            throw new InternalError("resolveAndCreate called with null context");
        }
        
        try {
            Type type = findType(call.className, ctx);
            if (type == null) {
                throw new ProgramError(
                    "Type not found: " + call.className + "\n" +
                    "Available types: " + getAvailableTypes(ctx)
                );
            }

            if (type.isUnsafe && !isUnsafeExecutionContext(ctx) && !ExecutionContext.isUnsafeCommitAllowed()) {
                throw new ProgramError(
                    "Unsafe class '" + type.name + "' cannot be constructed in a safe context. Use safe(" + type.name + "(...)).");
            }
            
            validateInheritanceHierarchy(type, ctx);
            
            buildFlattenedMethodTable(type, ctx);
            buildConstructorSignatureCache(type);
            
            ConstructorMatch match = findBestMatchingConstructor(type, call, ctx);
            if (match == null) {
                throw new ProgramError(
                    "No matching constructor found for " + type.name + "\n" +
                    "Arguments: " + formatArguments(call) + "\n" +
                    "Available constructors:\n" + formatConstructors(type.constructors)
                );
            }
            
            return createInstance(type, match, call, ctx);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Constructor resolution failed for: " + 
                (call != null ? call.className : "null"), e);
        }
    }
    
    private String getAvailableTypes(ExecutionContext ctx) {
        StringBuilder sb = new StringBuilder();
        Program currentProgram = interpreter.getCurrentProgram();
        if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.types != null) {
            for (Type type : currentProgram.unit.types) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(type.name);
            }
        }
        return sb.toString();
    }
    
    private String formatArguments(ConstructorCall call) {
        if (call.arguments == null || call.arguments.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < call.arguments.size(); i++) {
            if (i > 0) sb.append(", ");
            if (call.argNames != null && i < call.argNames.size() && call.argNames.get(i) != null) {
                sb.append(call.argNames.get(i)).append(": ");
            }
            sb.append("?");
        }
        return sb.toString();
    }
    
    private String formatConstructors(List<Constructor> constructors) {
        if (constructors == null || constructors.isEmpty()) {
            return "  () [default constructor]\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Constructor c : constructors) {
            sb.append("  ").append(ASTFactory.getConstructorSignature(c)).append("\n");
        }
        return sb.toString();
    }
    
    private void validateInheritanceHierarchy(Type type, ExecutionContext ctx) {
        if (type.extendName == null) {
            return;
        }
        
        Set<String> visited = new HashSet<String>();
        visited.add(type.name);
        
        String current = type.extendName;
        while (current != null) {
            if (visited.contains(current)) {
                throw new ProgramError(
                    "Circular inheritance detected: " + 
                    String.join(" -> ", visited) + " -> " + current
                );
            }
            
            visited.add(current);
            
            Type nextType = findType(current, ctx);
            if (nextType == null) {
                throw new ProgramError("Parent class not found: " + current);
            }
            
            if (nextType.extendName == null) {
                break;
            }
            
            current = nextType.extendName;
        }
    }
    
    private Type findType(String className, ExecutionContext ctx) {
        if (className == null) {
            throw new InternalError("findType called with null className");
        }
        
        Program currentProgram = interpreter.getCurrentProgram();
        if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.types != null) {
            for (Type type : currentProgram.unit.types) {
                if (type.name.equals(className)) {
                    return type;
                }
            }
        }
        
        if (importResolver != null) {
            try {
                Type type = importResolver.findType(className);
                if (type != null) {
                    return type;
                }
            } catch (ProgramError e) {
                DebugSystem.debug("CONSTRUCTOR", "Type not found in imports: " + className);
            }
        }
        
        return null;
    }
    
    private ConstructorMatch findBestMatchingConstructor(Type type, 
                                                       ConstructorCall call,
                                                       ExecutionContext ctx) {
        String typeKey = type.name;
        Map<String, Constructor> signatureMap = constructorSignatureCache.get(typeKey);
        
        if (signatureMap == null) {
            buildConstructorSignatureCache(type);
            signatureMap = constructorSignatureCache.get(typeKey);
        }
        
        String callSignature = buildCallSignature(call);
        Constructor exactMatch = signatureMap.get(callSignature);
        if (exactMatch != null) {
            Map<String, Object> argValues = matchConstructorArguments(exactMatch, call, ctx);
            if (argValues != null) {
                return new ConstructorMatch(exactMatch, argValues, 0);
            }
        }
        
        if (type.constructors == null || type.constructors.isEmpty()) {
            Constructor defaultConstructor = signatureMap.get("()");
            return new ConstructorMatch(defaultConstructor, Collections.<String, Object>emptyMap(), 0);
        }
        
        List<ConstructorMatch> candidates = new ArrayList<ConstructorMatch>();
        
        for (Constructor constructor : type.constructors) {
            ConstructorMatch match = tryMatchConstructor(constructor, call, ctx);
            if (match != null) {
                candidates.add(match);
            }
        }
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        return selectBestMatch(candidates);
    }
    
    private String buildCallSignature(ConstructorCall call) {
        if (call.arguments == null || call.arguments.isEmpty()) {
            return "()";
        }
        
        StringBuilder sb = new StringBuilder("(");
        sb.append("args").append(call.arguments.size());
        sb.append(")");
        return sb.toString();
    }
    
    private Map<String, Object> matchConstructorArguments(Constructor constructor,
                                                         ConstructorCall call,
                                                         ExecutionContext ctx) {
        if (constructor.parameters == null || constructor.parameters.isEmpty()) {
            return new HashMap<String, Object>();
        }
        
        Map<String, Object> argValues = new HashMap<String, Object>();
        Evaluator evaluator = interpreter.getVisitor();
        
        if (call.argNames != null && !call.argNames.isEmpty() && call.argNames.get(0) != null) {
            Map<String, Expr> namedArgs = new HashMap<String, Expr>();
            for (int i = 0; i < call.arguments.size(); i++) {
                namedArgs.put(call.argNames.get(i), call.arguments.get(i));
            }
            
            for (Param param : constructor.parameters) {
                if (namedArgs.containsKey(param.name)) {
                    Expr argExpr = namedArgs.get(param.name);
                    Object argValue = evaluator.evaluate(argExpr, ctx);
                    if (!typeSystem.validateType(param.type, argValue)) {
                        return null;
                    }
                    argValue = typeSystem.normalizeForDeclaredType(param.type, argValue);
                    argValues.put(param.name, argValue);
                } else if (!param.hasDefaultValue) {
                    return null;
                }
            }
            
            return argValues;
        }
        
        if (call.arguments.size() > constructor.parameters.size()) {
            return null;
        }
        
        for (int i = 0; i < constructor.parameters.size(); i++) {
            Param param = constructor.parameters.get(i);
            
            if (i < call.arguments.size()) {
                Expr argExpr = call.arguments.get(i);
                Object argValue = evaluator.evaluate(argExpr, ctx);
                if (!typeSystem.validateType(param.type, argValue)) {
                    return null;
                }
                argValue = typeSystem.normalizeForDeclaredType(param.type, argValue);
                argValues.put(param.name, argValue);
            } else if (!param.hasDefaultValue) {
                return null;
            }
        }
        
        return argValues;
    }
    
    private ConstructorMatch tryMatchConstructor(Constructor constructor,
                                               ConstructorCall call,
                                               ExecutionContext ctx) {
        try {
            if (call.argNames != null && !call.argNames.isEmpty() && call.argNames.get(0) != null) {
                return tryMatchNamedConstructor(constructor, call, ctx);
            }
            return tryMatchPositionalConstructor(constructor, call, ctx);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Constructor matching failed", e);
        }
    }
    
    private ConstructorMatch tryMatchNamedConstructor(Constructor constructor,
                                                    ConstructorCall call,
                                                    ExecutionContext ctx) {
        Map<String, Object> argumentValues = new HashMap<String, Object>();
        Map<String, Expr> providedArgs = new HashMap<String, Expr>();
        int conversionScore = 0;
        
        Evaluator evaluator = interpreter.getVisitor();
        
        for (int i = 0; i < call.arguments.size(); i++) {
            providedArgs.put(call.argNames.get(i), call.arguments.get(i));
        }
        
        for (Param param : constructor.parameters) {
            if (providedArgs.containsKey(param.name)) {
                Expr argExpr = providedArgs.get(param.name);
                Object argValue = evaluateArgument(evaluator, argExpr, param, ctx);
                
                if (argValue == null) {
                    return null;
                }
                
                if (!typeSystem.validateType(param.type, argValue)) {
                    return null;
                }
                argValue = typeSystem.normalizeForDeclaredType(param.type, argValue);
                
                argumentValues.put(param.name, argValue);
                
                if (typeSystem.getConcreteType(typeSystem.unwrap(argValue)).equals("text") && 
                    !param.type.equals("text")) {
                    conversionScore++;
                }
            } else if (param.hasDefaultValue) {
                Object defaultValue = evaluateDefaultValue(evaluator, param, ctx);
                if (param.type != null) {
                    defaultValue = typeSystem.normalizeForDeclaredType(param.type, defaultValue);
                }
                argumentValues.put(param.name, defaultValue);
                conversionScore++;
            } else {
                return null;
            }
        }
        
        for (String argName : call.argNames) {
            boolean found = false;
            for (Param param : constructor.parameters) {
                if (param.name.equals(argName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }
        
        return new ConstructorMatch(constructor, argumentValues, conversionScore);
    }
    
    private ConstructorMatch tryMatchPositionalConstructor(Constructor constructor,
                                                         ConstructorCall call,
                                                         ExecutionContext ctx) {
        Map<String, Object> argumentValues = new HashMap<String, Object>();
        int conversionScore = 0;
        int argIndex = 0;
        int paramIndex = 0;
        
        Evaluator evaluator = interpreter.getVisitor();
        
        while (paramIndex < constructor.parameters.size()) {
            Param param = constructor.parameters.get(paramIndex);
            
            if (argIndex < call.arguments.size()) {
                Expr argExpr = call.arguments.get(argIndex);
                
                if (isUnderscore(argExpr)) {
                    if (!param.hasDefaultValue) {
                        return null;
                    }
                    Object defaultValue = evaluateDefaultValue(evaluator, param, ctx);
                    if (param.type != null) {
                        defaultValue = typeSystem.normalizeForDeclaredType(param.type, defaultValue);
                    }
                    argumentValues.put(param.name, defaultValue);
                    conversionScore++;
                } else {
                    Object argValue = evaluateArgument(evaluator, argExpr, param, ctx);
                    
                    if (argValue == null) {
                        return null;
                    }
                    
                    if (!typeSystem.validateType(param.type, argValue)) {
                        return null;
                    }
                    argValue = typeSystem.normalizeForDeclaredType(param.type, argValue);
                    
                    argumentValues.put(param.name, argValue);
                    
                    if (typeSystem.getConcreteType(typeSystem.unwrap(argValue)).equals("text") && 
                        !param.type.equals("text")) {
                        conversionScore++;
                    }
                }
                argIndex++;
            } else if (param.hasDefaultValue) {
                Object defaultValue = evaluateDefaultValue(evaluator, param, ctx);
                if (param.type != null) {
                    defaultValue = typeSystem.normalizeForDeclaredType(param.type, defaultValue);
                }
                argumentValues.put(param.name, defaultValue);
                conversionScore++;
            } else {
                return null;
            }
            
            paramIndex++;
        }
        
        if (argIndex < call.arguments.size()) {
            return null;
        }
        
        return new ConstructorMatch(constructor, argumentValues, conversionScore);
    }
    
    private Object evaluateArgument(Evaluator evaluator, Expr argExpr, Param param, ExecutionContext ctx) {
        try {
            ExecutionContext argEvalCtx = new ExecutionContext(
                ctx.objectInstance, 
                new HashMap<String, Object>(), 
                null, 
                null,
                ctx.getTypeHandler()
            );
            
            for (int i = 0; i < ctx.getScopeDepth(); i++) {
                Map<String, Object> scope = ctx.getScope(i);
                if (scope != null) {
                    if (i >= argEvalCtx.getScopeDepth()) {
                        argEvalCtx.pushScope();
                    }
                    argEvalCtx.getScope(i).putAll(scope);
                }
            }
            
            Object argValue = evaluator.evaluate(argExpr, argEvalCtx);
            
            if (!typeSystem.validateType(param.type, argValue)) {
                if (param.type.equals(TEXT.toString())) {
                    return typeSystem.convertType(argValue, param.type);
                }
                return null;
            }

            argValue = typeSystem.normalizeForDeclaredType(param.type, argValue);
            
            if (param.type.contains("|")) {
    int activeMask = typeSystem.getConcreteMask(typeSystem.unwrap(argValue));
    int declaredMask = TypeHandler.parseTypeMask(param.type);
    return new TypeHandler.Value(argValue, activeMask, declaredMask);
}
            
            return argValue;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Argument evaluation failed for parameter: " + param.name, e);
        }
    }
    
    private Object evaluateDefaultValue(Evaluator evaluator, Param param, ExecutionContext ctx) {
        try {
            ExecutionContext defaultValueCtx = new ExecutionContext(
                ctx.objectInstance, 
                new HashMap<String, Object>(), 
                null, 
                null,
                ctx.getTypeHandler()
            );
            
            return evaluator.evaluate(param.defaultValue, defaultValueCtx);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Default value evaluation failed for parameter: " + param.name, e);
        }
    }

    private boolean isUnsafeExecutionContext(ExecutionContext ctx) {
        if (ctx == null) return false;
        if (ctx.currentClass != null && ctx.currentClass.isUnsafe) {
            return true;
        }
        if (ctx.currentMethodName == null || ctx.currentMethodName.isEmpty()) {
            return false;
        }
        Type searchType = ctx.currentClass;
        if (searchType == null && ctx.objectInstance != null) {
            searchType = ctx.objectInstance.type;
        }
        if (searchType == null) return false;
        Method currentMethod = findMethodInHierarchy(searchType, ctx.currentMethodName, ctx);
        return currentMethod != null && currentMethod.isUnsafe;
    }
    
    private boolean isUnderscore(Expr expr) {
        return expr instanceof Identifier && "_".equals(((Identifier) expr).name);
    }
    
    private ConstructorMatch selectBestMatch(List<ConstructorMatch> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        
        ConstructorMatch bestMatch = candidates.get(0);
        int bestScore = bestMatch.conversionScore;
        int bestArgCount = bestMatch.argumentValues.size();
        
        for (int i = 1; i < candidates.size(); i++) {
            ConstructorMatch current = candidates.get(i);
            int currentScore = current.conversionScore;
            int currentArgCount = current.argumentValues.size();
            
            if (currentScore < bestScore) {
                bestMatch = current;
                bestScore = currentScore;
                bestArgCount = currentArgCount;
            } else if (currentScore == bestScore && currentArgCount > bestArgCount) {
                bestMatch = current;
                bestArgCount = currentArgCount;
            }
        }
        
        return bestMatch;
    }
    
    public void validateViralPolicies(Type type, ExecutionContext ctx) {
        if (type == null) {
            throw new InternalError("validateViralPolicies called with null type");
        }
        
        try {
            boolean valid = policyResolver.validateClassPolicies(type, ctx);
            
            if (!valid) {
                Set<String> requiredPolicies = policyResolver.getClassPolicies(type, ctx);
                for (String policyName : requiredPolicies) {
                    Policy policy = findPolicy(policyName, ctx);
                    if (policy != null) {
                        List<PolicyMethod> requiredMethods = 
                            policyResolver.getFlattenedPolicyMethods(policy);
                        
                        for (PolicyMethod requiredMethod : requiredMethods) {
                            boolean implementsMethod = false;
                            
                            Map<String, Method> methodTable = flattenedMethodTables.get(type.name);
                            if (methodTable != null) {
                                Method classMethod = methodTable.get(requiredMethod.methodName);
                                if (classMethod != null && classMethod.isPolicyMethod) {
                                    implementsMethod = true;
                                }
                            }
                            
                            if (!implementsMethod) {
                                throw new ProgramError(
                                    "Missing policy method implementation: '" + requiredMethod.methodName + 
                                    "' required by policy '" + policyName + "'"
                                );
                            }
                        }
                    }
                }
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Policy validation failed for type: " + type.name, e);
        }
    }
    
    private Policy findPolicy(String policyName, ExecutionContext ctx) {
        if (importResolver != null) {
            try {
                Policy policy = importResolver.findPolicy(policyName);
                if (policy != null) {
                    return policy;
                }
            } catch (ProgramError e) {
                DebugSystem.debug("POLICY", "Policy not found in imports: " + policyName);
            }
        }
        
        Program currentProgram = interpreter.getCurrentProgram();
        if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.policies != null) {
            for (Policy policy : currentProgram.unit.policies) {
                if (policy.name.equals(policyName)) {
                    return policy;
                }
            }
        }
        
        return null;
    }
    
    private ObjectInstance createInstance(Type type, 
                                         ConstructorMatch match,
                                         ConstructorCall call,
                                         ExecutionContext ctx) {
        if (type == null) {
            throw new InternalError("createInstance called with null type");
        }
        if (match == null) {
            throw new InternalError("createInstance called with null match");
        }
        
        try {
            ObjectInstance obj = new ObjectInstance(type);
            
            validateViralPolicies(type, ctx);
            
            List<Type> inheritanceChain = getInheritanceChainCached(type, ctx);
            for (Type chainType : inheritanceChain) {
                initializeFields(chainType, obj, ctx);
            }
            
            ExecutionContext constrCtx = new ExecutionContext(obj, new HashMap<String, Object>(), null, null, ctx.getTypeHandler());
            constrCtx.currentClass = type;
            constrCtx.setUnsafeExecutionContext(type.isUnsafe);
            
            for (Map.Entry<String, Object> entry : match.argumentValues.entrySet()) {
                constrCtx.setVariable(entry.getKey(), entry.getValue());
            }
            
            for (Param param : match.constructor.parameters) {
                constrCtx.setVariableType(param.name, param.type);
            }
            
            boolean explicitSuperCalled = false;
            Evaluator evaluator = interpreter.getVisitor();
            
            if (!match.constructor.body.isEmpty() && match.constructor.body.get(0) instanceof MethodCall) {
                MethodCall firstCall = (MethodCall) match.constructor.body.get(0);
                if (firstCall.isSuperCall) {
                    explicitSuperCalled = true;
                    invokeSuperConstructor(type, firstCall, obj, ctx, constrCtx, evaluator);
                }
            }
            
            if (!explicitSuperCalled && type.extendName != null) {
                Type parentType = findParentType(type, ctx);
                if (parentType != null) {
                    ConstructorMatch parentMatch = findDefaultConstructor(parentType);
                    if (parentMatch != null) {
                        invokeParentConstructorSilently(parentType, parentMatch, obj, ctx, constrCtx, evaluator);
                    }
                }
            }
            
            int startIndex = explicitSuperCalled ? 1 : 0;
            for (int i = startIndex; i < match.constructor.body.size(); i++) {
                Stmt stmt = match.constructor.body.get(i);
                evaluator.evaluate(stmt, constrCtx);
            }
            
            return obj;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Instance creation failed for type: " + type.name, e);
        }
    }

    private void invokeSuperConstructor(Type childType, MethodCall superCall, 
                                        ObjectInstance childObj, ExecutionContext childCtx,
                                        ExecutionContext constrCtx, Evaluator evaluator) {
        try {
            Type parentType = findParentType(childType, childCtx);
            if (parentType == null) {
                throw new ProgramError(
                    "Cannot call super constructor: parent type not found for " + childType.name
                );
            }
            
            ConstructorCall parentCall = new ConstructorCall();
            parentCall.className = parentType.name;
            parentCall.arguments = superCall.arguments;
            parentCall.argNames = superCall.argNames;
            
            ConstructorMatch parentMatch = findBestMatchingConstructor(parentType, parentCall, childCtx);
            if (parentMatch == null) {
                throw new ProgramError(
                    "No matching parent constructor found for arguments\n" +
                    "Parent class: " + parentType.name + "\n" +
                    "Arguments: " + formatArguments(parentCall) + "\n" +
                    "Available constructors:\n" + formatConstructors(parentType.constructors)
                );
            }
            
            ExecutionContext parentConstrCtx = new ExecutionContext(childObj, new HashMap<String, Object>(), null, null, childCtx.getTypeHandler());
            
            for (Map.Entry<String, Object> entry : parentMatch.argumentValues.entrySet()) {
                parentConstrCtx.setVariable(entry.getKey(), entry.getValue());
            }
            
            for (Param param : parentMatch.constructor.parameters) {
                parentConstrCtx.setVariableType(param.name, param.type);
            }
            
            for (Stmt stmt : parentMatch.constructor.body) {
                evaluator.evaluate(stmt, parentConstrCtx);
            }
            
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Super constructor invocation failed", e);
        }
    }

    private void invokeParentConstructorSilently(Type parentType, ConstructorMatch parentMatch,
                                                ObjectInstance childObj, ExecutionContext childCtx,
                                                ExecutionContext constrCtx, Evaluator evaluator) {
        try {
            ExecutionContext parentConstrCtx = new ExecutionContext(childObj, new HashMap<String, Object>(), null, null, childCtx.getTypeHandler());
            
            for (Map.Entry<String, Object> entry : parentMatch.argumentValues.entrySet()) {
                parentConstrCtx.setVariable(entry.getKey(), entry.getValue());
            }
            
            for (Param param : parentMatch.constructor.parameters) {
                parentConstrCtx.setVariableType(param.name, param.type);
            }
            
            for (Stmt stmt : parentMatch.constructor.body) {
                evaluator.evaluate(stmt, parentConstrCtx);
            }
            
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Parent constructor invocation failed", e);
        }
    }

    private ConstructorMatch findDefaultConstructor(Type type) {
        String typeKey = type.name;
        Map<String, Constructor> signatureMap = constructorSignatureCache.get(typeKey);
        
        if (signatureMap != null && signatureMap.containsKey("()")) {
            Constructor defaultConstructor = signatureMap.get("()");
            return new ConstructorMatch(defaultConstructor, Collections.<String, Object>emptyMap(), 0);
        }
        
        if (type.constructors == null || type.constructors.isEmpty()) {
            Constructor defaultConstructor = createDefaultConstructor(type);
            return new ConstructorMatch(defaultConstructor, Collections.<String, Object>emptyMap(), 0);
        }
        
        for (Constructor constructor : type.constructors) {
            if (constructor.parameters == null || constructor.parameters.isEmpty()) {
                return new ConstructorMatch(constructor, Collections.<String, Object>emptyMap(), 0);
            }
        }
        
        return null;
    }
    
    private void initializeFields(Type type, ObjectInstance obj, ExecutionContext ctx) {
        try {
            ExecutionContext fieldCtx = new ExecutionContext(obj, new HashMap<String, Object>(), null, null, ctx.getTypeHandler());
            Evaluator evaluator = interpreter.getVisitor();
            
            Map<String, Field> fieldTable = flattenedFieldTables.get(type.name);
            
            if (fieldTable != null) {
                for (Map.Entry<String, Field> entry : fieldTable.entrySet()) {
                    Field field = entry.getValue();
                    if (field.value != null) {
                        Object defaultValue = evaluator.evaluate(field.value, fieldCtx);
                        obj.fields.put(field.name, defaultValue);
                    } else {
                        String fieldType = field.type;
                        if (fieldType != null && typeSystem.isSizedArrayType(fieldType)) {
                            int length = typeSystem.getSizedArrayLength(fieldType);
                            int sizedLength = Math.max(length, 0);
                            String elementType = typeSystem.getSizedArrayElementType(fieldType);
                            List<Object> initialized = new ArrayList<Object>(sizedLength);
                            Object elementDefault = 0;
                            if (elementType != null && typeSystem.isUnsafeNumericType(elementType)) {
                                elementDefault = typeSystem.convertType(0, elementType);
                            } else if (elementType != null && elementType.contains(FLOAT.toString())) {
                                elementDefault = 0.0;
                            } else if (elementType != null && elementType.contains(TEXT.toString())) {
                                elementDefault = "";
                            } else if (elementType != null && elementType.contains(BOOL.toString())) {
                                elementDefault = false;
                            }
                            for (int i = 0; i < sizedLength; i++) {
                                initialized.add(elementDefault);
                            }
                            obj.fields.put(field.name, initialized);
                        } else if (fieldType != null && typeSystem.isPointerType(fieldType)) {
                            obj.fields.put(field.name, null);
                        } else if (fieldType != null && typeSystem.isUnsafeNumericType(fieldType)) {
                            obj.fields.put(field.name, typeSystem.convertType(0, fieldType));
                        } else if (fieldType != null && fieldType.contains(INT.toString())) {
                            obj.fields.put(field.name, 0);
                        } else if (fieldType != null && fieldType.contains(FLOAT.toString())) {
                            obj.fields.put(field.name, 0.0);
                        } else if (fieldType != null && fieldType.contains(TEXT.toString())) {
                            obj.fields.put(field.name, "");
                        } else if (fieldType != null && fieldType.contains(BOOL.toString())) {
                            obj.fields.put(field.name, false);
                        } else {
                            obj.fields.put(field.name, null);
                        }
                    }
                }
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Field initialization failed for type: " + type.name, e);
        }
    }
    
    private Constructor createDefaultConstructor(Type type) {
        Constructor defaultConstructor = new Constructor();
        defaultConstructor.parameters = new ArrayList<Param>();
        defaultConstructor.body = new ArrayList<Stmt>();
        
        for (Field field : type.fields) {
            if (field.value != null) {
                Expr fieldTarget = ASTFactory.createIdentifier(field.name, null);
                Assignment fieldInit = ASTFactory.createAsmt(fieldTarget, field.value, false, null);
                defaultConstructor.body.add(fieldInit);
            }
        }
        
        return defaultConstructor;
    }
    
    public Object getFieldFromHierarchy(Type type, String fieldName, ExecutionContext ctx) {
        if (type == null) {
            throw new InternalError("getFieldFromHierarchy called with null type");
        }
        if (fieldName == null) {
            throw new InternalError("getFieldFromHierarchy called with null fieldName");
        }
        
        try {
            String actualFieldName = fieldName;
            if (fieldName != null && fieldName.startsWith("this.")) {
                actualFieldName = fieldName.substring(5);
            }
            
            Map<String, Field> fieldTable = flattenedFieldTables.get(type.name);
            if (fieldTable != null && fieldTable.containsKey(actualFieldName)) {
                return ctx.objectInstance.fields.get(actualFieldName);
            }
            
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Field lookup failed: " + fieldName, e);
        }
    }
    
    public Method findMethodInHierarchy(Type type, String methodName, ExecutionContext ctx) {
        if (type == null) {
            throw new InternalError("findMethodInHierarchy called with null type");
        }
        if (methodName == null) {
            throw new InternalError("findMethodInHierarchy called with null methodName");
        }
        
        try {
            buildFlattenedMethodTable(type, ctx);
            
            Map<String, Method> methodTable = flattenedMethodTables.get(type.name);
            if (methodTable != null) {
                return methodTable.get(methodName);
            }
            
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Method lookup failed: " + methodName, e);
        }
    }
    
    public Type findParentType(Type childType, ExecutionContext ctx) {
        if (childType.extendName == null) {
            return null;
        }
        
        if (importResolver != null) {
            try {
                Type parent = importResolver.findType(childType.extendName);
                if (parent != null) {
                    return parent;
                }
            } catch (ProgramError e) {
                DebugSystem.debug("CONSTRUCTOR", "Parent type not found in imports: " + childType.extendName);
            }
        }
        
        Program currentProgram = interpreter.getCurrentProgram();
        if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.types != null) {
            for (Type type : currentProgram.unit.types) {
                if (type.name.equals(childType.extendName)) {
                    return type;
                }
            }
        }
        
        return null;
    }
    
    public void clearCaches() {
        flattenedMethodTables.clear();
        flattenedFieldTables.clear();
        inheritanceChainCache.clear();
        constructorSignatureCache.clear();
        if (policyResolver != null) {
            policyResolver.clearCaches();
        }
        DebugSystem.debug("CONSTRUCTOR", "All caches cleared");
    }
    
    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<String, Integer>();
        stats.put("flattenedMethodTables", flattenedMethodTables.size());
        stats.put("flattenedFieldTables", flattenedFieldTables.size());
        stats.put("inheritanceChainCache", inheritanceChainCache.size());
        stats.put("constructorSignatureCache", constructorSignatureCache.size());
        if (policyResolver != null) {
            stats.putAll(policyResolver.getCacheStats());
        }
        return stats;
    }
    
    private static class ConstructorMatch {
        final Constructor constructor;
        final Map<String, Object> argumentValues;
        final int conversionScore;
        
        ConstructorMatch(Constructor constructor, Map<String, Object> argumentValues, int conversionScore) {
            if (constructor == null) {
                throw new InternalError("ConstructorMatch constructed with null constructor");
            }
            if (argumentValues == null) {
                throw new InternalError("ConstructorMatch constructed with null argumentValues");
            }
            
            this.constructor = constructor;
            this.argumentValues = argumentValues;
            this.conversionScore = conversionScore;
        }
    }
}
