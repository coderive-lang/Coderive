package cod.semantic;

import cod.ast.node.*;
import cod.error.ParseError;
import cod.interpreter.Interpreter;
import cod.lexer.Token;
import cod.parser.DeclarationParser;
import cod.parser.MainParser.ProgramType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cod.semantic.ObjectValidator.nil;

public final class ModuleValidator {

    private static final String DEFAULT_UNIT_NAME = "default";
    private static final String SELF_BROADCAST_NAME = "this";

    private ModuleValidator() {}

    public static ProgramType determineProgramType(Program program,
                                                   List<Stmt> topLevelStatements,
                                                   List<Method> topLevelMethods,
                                                   List<Type> typesInFile,
                                                   List<Policy> policiesInFile,
                                                   Token errorToken) {

        boolean hasUnit = program.unit.name != null && !program.unit.name.equals(DEFAULT_UNIT_NAME);

        List<Stmt> actualStatements = new ArrayList<>();
        for (Stmt stmt : topLevelStatements) {
            if (stmt instanceof Block) {
                Block block = (Block) stmt;
                if (!block.statements.isEmpty()) {
                    actualStatements.add(stmt);
                }
            } else if (stmt != null) {
                actualStatements.add(stmt);
            }
        }

        boolean hasDirectCode = !actualStatements.isEmpty();
        boolean hasMethods = !topLevelMethods.isEmpty();
        boolean hasClasses = !typesInFile.isEmpty();
        boolean hasPolicies = !policiesInFile.isEmpty();
        boolean hasMainClassDeclaration = !nil(program.unit.mainClassName);
        boolean isSelfBroadcast = hasMainClassDeclaration && SELF_BROADCAST_NAME.equals(program.unit.mainClassName);

        if (hasDirectCode) {
            if (hasMethods) {
                throw error("Cannot mix method declarations with direct code. Use a class or unit.", errorToken);
            }
            if (hasClasses || hasPolicies) {
                throw error("Cannot mix class/policy declarations with direct script code in the same file.", errorToken);
            }
            if (hasUnit && !isSelfBroadcast) {
                throw error(
                    "Script unit declarations are only allowed with self broadcast: unit <name> (main: this)",
                    errorToken
                );
            }
            return ProgramType.SCRIPT;
        }

        if (hasMethods) {
            if (!hasUnit) {
                throw error("Static modules with top-level methods must declare a unit.", errorToken);
            }
            if (isSelfBroadcast) {
                throw error("Self broadcast (main: this) is only valid for script files with direct code.", errorToken);
            }
            return ProgramType.STATIC_MODULE;
        }

        if (hasClasses || hasPolicies) {
            if (hasUnit && isSelfBroadcast) {
                throw error("Self broadcast (main: this) is only valid for script files with direct code.", errorToken);
            }
            return ProgramType.MODULE;
        }

        if (hasUnit) {
            if (isSelfBroadcast) {
                return ProgramType.SCRIPT;
            }
            return ProgramType.STATIC_MODULE;
        }

        return ProgramType.SCRIPT;
    }

    public static void validateModule(Program program,
                                      List<Type> typesInFile,
                                      List<Policy> policiesInFile,
                                      Interpreter interpreter,
                                      DeclarationParser declarationParser,
                                      Token errorToken) {
        if (interpreter != null) {
            String filePath = interpreter.getCurrentFilePath();
            if (filePath != null) {
                validateUnitAgainstFilePath(program.unit.name, filePath, errorToken);
            }
        }

        if (interpreter != null && !nil(program.unit.mainClassName)) {
            try {
                String packageName = extractPackageName(program.unit.name);
                interpreter.getImportResolver().registerBroadcast(
                    packageName, program.unit.mainClassName
                );
            } catch (Exception e) {
                // Ignore
            }
        }

        validateMainClassExistsInFile(program.unit, typesInFile, errorToken);
        validateImplementedPolicies(typesInFile, policiesInFile, errorToken);

        for (Type type : typesInFile) {
            declarationParser.validateAllPolicyMethods(type, program);
            declarationParser.validateClassViralPolicies(type, program);
        }
    }

    private static void validateUnitAgainstFilePath(String unitName, String filePath, Token errorToken) {
        if (nil(filePath, unitName)) return;

        if (unitName.isEmpty()) {
            throw error("Unit name cannot be empty", errorToken);
        }

        if (!filePath.endsWith(".cod")) {
            throw error("File must have .cod extension", errorToken);
        }

        String normalizedPath = filePath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        String directoryPath = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
        String expectedFolderPath = unitName.replace('.', '/');
        boolean matchesUnitFolder =
            directoryPath.equals(expectedFolderPath) ||
            directoryPath.endsWith("/" + expectedFolderPath);

        if (!matchesUnitFolder) {
            throw error(
                "Unit name '" + unitName + "' must match containing folder path '" + expectedFolderPath + "'",
                errorToken
            );
        }
    }

    private static void validateMainClassExistsInFile(Unit unit, List<Type> typesInFile, Token errorToken) {
        if (unit.mainClassName == null || unit.mainClassName.isEmpty()) {
            return;
        }

        boolean classFound = false;
        for (Type type : typesInFile) {
            if (Objects.equals(unit.mainClassName, type.name)) {
                classFound = true;

                boolean hasMainMethod = false;
                for (Method method : type.methods) {
                    if ("main".equals(method.methodName)) {
                        hasMainMethod = true;
                        break;
                    }
                }

                if (!hasMainMethod) {
                    throw error(
                        "[MODULE] Broadcasted class '" + unit.mainClassName +
                        "' must have a main() method",
                        errorToken
                    );
                }
                break;
            }
        }

        if (!classFound) {
            throw error(
                "[MODULE] Cannot broadcast undefined class '" + unit.mainClassName + "'\n" +
                "Define " + unit.mainClassName + " in this file before broadcasting it\n" +
                "Example:\n" +
                "  unit " + unit.name + " (main: " + unit.mainClassName + ")\n" +
                "  \n" +
                "  " + unit.mainClassName + " {\n" +
                "      share main() {\n" +
                "          // Your code here\n" +
                "      }\n" +
                "  }",
                errorToken
            );
        }
    }

    private static void validateImplementedPolicies(List<Type> types, List<Policy> policies, Token errorToken) {
        Map<String, Policy> policyMap = new HashMap<>();
        for (Policy policy : policies) {
            policyMap.put(policy.name, policy);
        }

        for (Type type : types) {
            if (nil(type.implementedPolicies)) {
                continue;
            }
            for (String policyName : type.implementedPolicies) {
                if (!policyMap.containsKey(policyName)) {
                    throw error(
                        "Class '" + type.name + "' implements undefined policy '" + policyName + "'\n" +
                        "Available policies: " + policyMap.keySet(),
                        errorToken
                    );
                }
            }
        }
    }

    private static String extractPackageName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return "";
        }

        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot > 0) {
            return qualifiedName.substring(0, lastDot);
        }
        return qualifiedName;
    }

    private static ParseError error(String message, Token token) {
        if (token != null) {
            return new ParseError(message, token);
        }
        return new ParseError(message, 1, 1);
    }
}
