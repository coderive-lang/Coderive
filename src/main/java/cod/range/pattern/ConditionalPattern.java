package cod.range.pattern;

import cod.ast.node.*;
import java.util.*;

public class ConditionalPattern {
    public final Expr array;
    public final String indexVar;
    public final List<Branch> branches;
    public final List<Stmt> elseStatements;
    
    public static class Branch {
        public final Expr condition;
        public final List<Stmt> statements;
        
        public Branch(Expr condition, List<Stmt> statements) {
            this.condition = condition;
            this.statements = statements;
        }
    }
    
    public ConditionalPattern(Expr array, String indexVar,
                             List<Branch> branches,
                             List<Stmt> elseStatements) {
        this.array = array;
        this.indexVar = indexVar;
        this.branches = branches != null ? branches : new ArrayList<Branch>();
        this.elseStatements = elseStatements != null ? elseStatements : new ArrayList<Stmt>();
    }
    
    public boolean isOptimizable() {
        return array != null && indexVar != null && 
               branches != null && !branches.isEmpty();
    }
    
    public static ConditionalPattern extract(StmtIf ifStmt, String iterator) {
        List<ConditionalPattern> patterns = extractAll(ifStmt, iterator);
        if (patterns.isEmpty()) {
            return null;
        }
        return patterns.get(0);
    }
    
    /**
     * Extract one conditional pattern per target array from an if/elif/else chain.
     * @return list of patterns (one per unique target array) or an empty list when not extractable
     */
    public static List<ConditionalPattern> extractAll(StmtIf ifStmt, String iterator) {
        if (ifStmt == null || iterator == null) {
            return new ArrayList<ConditionalPattern>();
        }
        
        ChainExtraction chain = flattenChain(ifStmt);
        if (chain == null || chain.branches.isEmpty()) {
            return new ArrayList<ConditionalPattern>();
        }
        
        List<Expr> targetArrays = collectTargetArrays(chain, iterator);
        List<ConditionalPattern> results = new ArrayList<ConditionalPattern>();
        
        for (Expr targetArray : targetArrays) {
            List<Branch> filteredBranches = new ArrayList<Branch>();
            boolean hasAnyAssignment = false;
            
            for (Branch branch : chain.branches) {
                List<Stmt> filtered = filterStatementsForArray(branch.statements, iterator, targetArray);
                if (!filtered.isEmpty()) {
                    filteredBranches.add(new Branch(branch.condition, filtered));
                    if (containsArrayAssignment(filtered, iterator, targetArray)) {
                        hasAnyAssignment = true;
                    }
                }
            }
            
            List<Stmt> filteredElse = filterStatementsForArray(chain.elseStatements, iterator, targetArray);
            if (containsArrayAssignment(filteredElse, iterator, targetArray)) {
                hasAnyAssignment = true;
            }
            
            if (hasAnyAssignment && !filteredBranches.isEmpty()) {
                results.add(new ConditionalPattern(targetArray, iterator, filteredBranches, filteredElse));
            }
        }
        
        return results;
    }
    
    private static class ChainExtraction {
        final List<Branch> branches;
        final List<Stmt> elseStatements;
        
        ChainExtraction(List<Branch> branches, List<Stmt> elseStatements) {
            this.branches = branches;
            this.elseStatements = elseStatements;
        }
    }
    
    private static ChainExtraction flattenChain(StmtIf ifStmt) {
        List<Branch> branches = new ArrayList<Branch>();
        List<Stmt> elseStatements = new ArrayList<Stmt>();
        StmtIf current = ifStmt;
        
        while (current != null) {
            List<Stmt> thenStatements = current.thenBlock != null && current.thenBlock.statements != null
                ? current.thenBlock.statements
                : new ArrayList<Stmt>();
            branches.add(new Branch(current.condition, thenStatements));
            
            if (current.elseBlock == null || current.elseBlock.statements == null ||
                current.elseBlock.statements.isEmpty()) {
                break;
            }
            
            Stmt firstElse = current.elseBlock.statements.get(0);
            if (firstElse instanceof StmtIf) {
                current = (StmtIf) firstElse;
            } else {
                elseStatements = current.elseBlock.statements;
                break;
            }
        }
        
        return new ChainExtraction(branches, elseStatements);
    }
    
    private static List<Expr> collectTargetArrays(ChainExtraction chain, String iterator) {
        List<Expr> targets = new ArrayList<Expr>();
        Set<String> seenTargets = new HashSet<String>();
        
        for (Branch branch : chain.branches) {
            addTargetsFromStatements(targets, seenTargets, branch.statements, iterator);
        }
        addTargetsFromStatements(targets, seenTargets, chain.elseStatements, iterator);
        
        return targets;
    }
    
    private static void addTargetsFromStatements(List<Expr> targets, Set<String> seenTargets,
                                                 List<Stmt> statements, String iterator) {
        if (statements == null) {
            return;
        }
        
        for (Stmt stmt : statements) {
            Expr stmtArray = validateStatementTarget(stmt, iterator);
            if (stmtArray == null) {
                continue;
            }
            
            String key = arrayKey(stmtArray);
            if (seenTargets.add(key)) {
                targets.add(stmtArray);
            }
        }
    }
    
    private static String arrayKey(Expr arrayExpr) {
        if (arrayExpr instanceof Identifier) {
            return "id:" + ((Identifier) arrayExpr).name;
        }
        // Fallback key for non-identifiers; this is heuristic and based on node string form.
        return "expr:" + String.valueOf(arrayExpr);
    }
    
    private static List<Stmt> filterStatementsForArray(List<Stmt> statements, String iterator, Expr targetArray) {
        List<Stmt> filtered = new ArrayList<Stmt>();
        if (statements == null) {
            return filtered;
        }
        
        for (Stmt stmt : statements) {
            Expr stmtArray = validateStatementTarget(stmt, iterator);
            if (stmtArray == null) {
                if (isVariableDeclaration(stmt)) {
                    filtered.add(stmt);
                }
                continue;
            }
            
            if (isSameArray(stmtArray, targetArray)) {
                filtered.add(stmt);
            }
        }
        
        return filtered;
    }
    
    private static boolean isVariableDeclaration(Stmt stmt) {
        if (stmt instanceof Var) {
            return true;
        }
        
        if (stmt instanceof Assignment) {
            return ((Assignment) stmt).isDeclaration;
        }
        
        return false;
    }
    
    private static boolean containsArrayAssignment(List<Stmt> statements, String iterator, Expr targetArray) {
        if (statements == null) {
            return false;
        }
        
        for (Stmt stmt : statements) {
            Expr stmtArray = validateStatementTarget(stmt, iterator);
            if (stmtArray != null && isSameArray(stmtArray, targetArray)) {
                return true;
            }
        }
        
        return false;
    }

    private static Expr validateStatementTarget(Stmt stmt, String iterator) {
        if (stmt instanceof Assignment) {
            Assignment assign = (Assignment) stmt;
            if (assign.left instanceof IndexAccess) {
                IndexAccess indexAccess = (IndexAccess) assign.left;
                if (isIndexIterator(indexAccess.index, iterator)) {
                    return indexAccess.array;
                }
            }
        }
        // Var (variable declarations) are allowed but don't target array
        return null;
    }
    
    private static boolean isIndexIterator(Expr index, String iterator) {
        return index instanceof Identifier && 
               iterator.equals(((Identifier) index).name);
    }
    
    private static boolean isSameArray(Expr a, Expr b) {
        if (a == null || b == null) return false;
        if (a instanceof Identifier && b instanceof Identifier) {
            return ((Identifier) a).name.equals(((Identifier) b).name);
        }
        return a.toString().equals(b.toString());
    }
}
