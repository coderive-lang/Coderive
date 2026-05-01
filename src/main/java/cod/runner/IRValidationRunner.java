package cod.runner;

import cod.ast.node.*;
import cod.ir.IRManager;
import cod.ir.IRReader;
import cod.ir.IRWriter;
import cod.interpreter.Interpreter;
import cod.ptac.Artifact;
import cod.semantic.ImportResolver;

import java.io.File;
import java.util.Map;

public class IRValidationRunner extends BaseRunner {
    // Relative to Index project root when running demo files: src/main/cod/demo
    private static final String INTERNAL_RANGE_SPEC_RELATIVE_PATH = "../internal/range/RangeSpec.cod";
    private static final String INTERNAL_MULTI_RANGE_SPEC_RELATIVE_PATH = "../internal/range/MultiRangeSpec.cod";
    // Relative to Index project root; points to the demo import-probe test file.
    private static final String INTERNAL_IR_IMPORT_RELATIVE_PATH = "src/main/test/InternalRangeSpecImport.cod";

    @Override
    public void run(String[] args) throws Exception {
        String file = "/home/runner/work/Coderive/Coderive/src/main/cod/demo/src/main/test/Import.cod";
        if (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            file = args[0];
        }

        Interpreter interpreter = new Interpreter();
        interpreter.setFilePath(file);
        Program program = parse(file, interpreter);

        if (program == null || program.unit == null || program.unit.types == null || program.unit.types.isEmpty()) {
            throw new RuntimeException("No parsed type available for IR validation");
        }

        Type original = program.unit.types.get(0);
        File tmp = new File("/tmp/coderive-ir-validation.codb");

        IRWriter writer = new IRWriter();
        IRReader reader = new IRReader();
        writer.write(tmp, original);
        Artifact artifact = reader.readArtifact(tmp);
        Type loaded = artifact != null ? artifact.typeSnapshot : null;

        assertTrue(loaded != null, "Loaded type is null");
        assertTrue(equalsSafe(original.name, loaded.name), "Type name mismatch");
        assertTrue(loaded.methods != null, "Loaded methods null");
        assertTrue(original.methods != null, "Original methods null");
        assertTrue(original.methods.size() == loaded.methods.size(), "Method count mismatch");

        if (!original.methods.isEmpty()) {
            Method om = original.methods.get(0);
            Method lm = loaded.methods.get(0);
            assertTrue(equalsSafe(om.methodName, lm.methodName), "First method name mismatch");
            int ob = om.body == null ? 0 : om.body.size();
            int lb = lm.body == null ? 0 : lm.body.size();
            assertTrue(ob == lb, "First method body size mismatch");
        }

        String projectRoot = cod.interpreter.Index.getProjectRoot();
        if (projectRoot != null) {
            IRManager manager = new IRManager(projectRoot);
            manager.save(program.unit.name, original);
            Type managerLoaded = manager.load(program.unit.name, original.name);
            assertTrue(managerLoaded != null, "IRManager failed to load saved class");
            assertTrue(equalsSafe(original.name, managerLoaded.name), "IRManager loaded wrong class");
            Artifact managerArtifact = manager.loadArtifact(program.unit.name, original.name);
            assertTrue(managerArtifact != null, "IRManager failed to load saved CodP-TAC artifact");
            assertTrue(managerArtifact.unit != null, "CodP-TAC unit missing from artifact");
        }

        validateInternalImportIRPath();

        System.out.println("IR validation passed");
    }

    private void validateInternalImportIRPath() throws Exception {
        String codProjectRoot = cod.interpreter.Index.getProjectRoot();
        assertTrue(codProjectRoot != null, "Project root not resolved for internal IR validation");

        String internalFile = new File(codProjectRoot, INTERNAL_RANGE_SPEC_RELATIVE_PATH).getAbsolutePath();
        String internalMultiRangeFile = new File(codProjectRoot, INTERNAL_MULTI_RANGE_SPEC_RELATIVE_PATH).getAbsolutePath();
        String importProbeFile = new File(codProjectRoot, INTERNAL_IR_IMPORT_RELATIVE_PATH).getAbsolutePath();

        Interpreter internalInterpreter = new Interpreter();
        internalInterpreter.setFilePath(internalFile);
        Program internalProgram = parse(internalFile, internalInterpreter);
        assertTrue(internalProgram != null, "Internal source parse failed");
        assertTrue(internalProgram.unit != null, "Internal unit is null");
        assertTrue(internalProgram.unit.types != null && !internalProgram.unit.types.isEmpty(),
            "Internal type list is empty");

        IRManager manager = new IRManager(codProjectRoot);
        Type internalType = internalProgram.unit.types.get(0);
        manager.save(internalProgram.unit.name, internalType);
        Artifact artifact = manager.loadArtifact(internalProgram.unit.name, internalType.name);
        assertTrue(artifact != null, "Failed to save/load internal CodP-TAC artifact");
        
        Interpreter internalMultiRangeInterpreter = new Interpreter();
        internalMultiRangeInterpreter.setFilePath(internalMultiRangeFile);
        Program internalMultiRangeProgram = parse(internalMultiRangeFile, internalMultiRangeInterpreter);
        assertTrue(internalMultiRangeProgram != null, "Internal multi-range source parse failed");
        assertTrue(internalMultiRangeProgram.unit != null, "Internal multi-range unit is null");
        assertTrue(internalMultiRangeProgram.unit.types != null && !internalMultiRangeProgram.unit.types.isEmpty(),
            "Internal multi-range type list is empty");
        
        Type internalMultiRangeType = internalMultiRangeProgram.unit.types.get(0);
        manager.save(internalMultiRangeProgram.unit.name, internalMultiRangeType);
        Artifact multiArtifact = manager.loadArtifact(internalMultiRangeProgram.unit.name, internalMultiRangeType.name);
        assertTrue(multiArtifact != null, "Failed to save/load internal multi-range CodP-TAC artifact");

        ImportResolver resolver = new ImportResolver();
        // setCurrentFileDirectory accepts a file path and derives its parent directory.
        resolver.setCurrentFileDirectory(importProbeFile);
        resolver.clearCache();
        Type loaded = resolver.resolveImport("internal.range.RangeSpec");
        assertTrue(loaded != null, "ImportResolver failed to load internal.range.RangeSpec");
        Type loadedMulti = resolver.resolveImport("internal.range.MultiRangeSpec");
        assertTrue(loadedMulti != null, "ImportResolver failed to load internal.range.MultiRangeSpec");

        Map<String, Object> cacheStats = resolver.getCacheStats();
        Object bytecodeHits = cacheStats.get("bytecodeCacheHits");
        assertTrue(bytecodeHits instanceof Integer, "Missing bytecode cache hit stat");
        assertTrue(((Integer) bytecodeHits).intValue() == 2,
            "Expected exactly two IR bytecode cache hits when loading internal.range.RangeSpec and internal.range.MultiRangeSpec");
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException(message);
        }
    }

    public static void main(String[] args) {
        try {
            new IRValidationRunner().run(args);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
