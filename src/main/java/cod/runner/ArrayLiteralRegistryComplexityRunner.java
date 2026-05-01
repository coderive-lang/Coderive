package cod.runner;

import cod.ast.node.BoolLiteral;
import cod.ast.node.Expr;
import cod.ast.node.FloatLiteral;
import cod.ast.node.IntLiteral;
import cod.ast.node.NoneLiteral;
import cod.ast.node.Range;
import cod.ast.node.TextLiteral;
import cod.error.ProgramError;
import cod.interpreter.Evaluator;
import cod.interpreter.context.ExecutionContext;
import cod.interpreter.handler.TypeHandler;
import cod.interpreter.registry.LiteralRegistry;
import cod.range.NaturalArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Validates complexity characteristics for array literal registry operations.
 * Compares historical eager behavior to current NaturalArray-backed behavior.
 */
public final class ArrayLiteralRegistryComplexityRunner {
    private static final int[] SIZES = new int[] {1000, 10000, 100000, 10000000};
    private static final int SAMPLES = 4;

    public static void main(String[] args) {
        TypeHandler typeHandler = new TypeHandler();
        ExecutionContext ctx = new ExecutionContext(
            null,
            new HashMap<String, Object>(),
            new HashMap<String, Object>(),
            new HashMap<String, String>(),
            typeHandler
        );

        SimpleEvaluator evaluator = new SimpleEvaluator();
        LiteralRegistry registry = new LiteralRegistry(evaluator);

        List<Long> eagerMapTimes = new ArrayList<Long>();
        List<Long> lazyMapSetupTimes = new ArrayList<Long>();
        List<Long> eagerFilterTimes = new ArrayList<Long>();
        List<Long> lazyFilterSetupTimes = new ArrayList<Long>();
        List<Long> eagerReduceTimes = new ArrayList<Long>();
        List<Long> streamedReduceTimes = new ArrayList<Long>();

        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            NaturalArray array = createNumericNaturalArray(size, evaluator, ctx);

            long eagerMap = medianNs(runEagerMapBenchmark(array, typeHandler, SAMPLES));
            long lazyMapSetup = medianNs(runRegistryMapSetupBenchmark(array, registry, ctx, SAMPLES));

            long eagerFilter = medianNs(runEagerFilterBenchmark(array, typeHandler, SAMPLES));
            long lazyFilterSetup = medianNs(runRegistryFilterSetupBenchmark(array, registry, ctx, SAMPLES));

            long eagerReduce = medianNs(runEagerReduceBenchmark(array, typeHandler, SAMPLES));
            long streamedReduce = medianNs(runRegistryReduceBenchmark(array, registry, ctx, SAMPLES));

            eagerMapTimes.add(Long.valueOf(eagerMap));
            lazyMapSetupTimes.add(Long.valueOf(lazyMapSetup));
            eagerFilterTimes.add(Long.valueOf(eagerFilter));
            lazyFilterSetupTimes.add(Long.valueOf(lazyFilterSetup));
            eagerReduceTimes.add(Long.valueOf(eagerReduce));
            streamedReduceTimes.add(Long.valueOf(streamedReduce));

            System.out.println(
                "size=" + size
                    + " eagerMapNs=" + eagerMap
                    + " lazyMapSetupNs=" + lazyMapSetup
                    + " eagerFilterNs=" + eagerFilter
                    + " lazyFilterSetupNs=" + lazyFilterSetup
                    + " eagerReduceNs=" + eagerReduce
                    + " streamedReduceNs=" + streamedReduce
            );
        }

        double mapLazyGrowth = growthRatio(lazyMapSetupTimes);
        double mapEagerGrowth = growthRatio(eagerMapTimes);
        double filterLazyGrowth = growthRatio(lazyFilterSetupTimes);
        double filterEagerGrowth = growthRatio(eagerFilterTimes);

        assertTrue(
            mapLazyGrowth < mapEagerGrowth,
            "Expected lazy map setup growth to be smaller than eager map growth"
        );
        assertTrue(
            filterLazyGrowth < filterEagerGrowth,
            "Expected lazy filter setup growth to be smaller than eager filter growth"
        );

        System.out.println("mapLazyGrowth=" + mapLazyGrowth + " mapEagerGrowth=" + mapEagerGrowth);
        System.out.println("filterLazyGrowth=" + filterLazyGrowth + " filterEagerGrowth=" + filterEagerGrowth);
        System.out.println("Array literal registry complexity validation passed");
    }

    private static NaturalArray createNumericNaturalArray(int size, Evaluator evaluator, ExecutionContext ctx) {
        Range range = new Range(null, new IntLiteral(1), new IntLiteral(size));
        return new NaturalArray(range, evaluator, ctx);
    }

    private static List<Long> runEagerMapBenchmark(NaturalArray array, TypeHandler typeHandler, int samples) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            List<Object> source = array.toList();
            List<Object> mapped = new ArrayList<Object>(source.size());
            for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
                mapped.add(typeHandler.addNumbers(source.get(sourceIndex), Integer.valueOf(1)));
            }
            consume(mapped.size());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static List<Long> runRegistryMapSetupBenchmark(
        NaturalArray array,
        LiteralRegistry registry,
        ExecutionContext ctx,
        int samples
    ) {
        List<Long> out = new ArrayList<Long>();
        List<Object> args = new ArrayList<Object>();
        args.add("+");
        args.add(Integer.valueOf(1));
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            Object mapped = registry.handleMethod(array, "map", args, ctx);
            consume(mapped.getClass().getName().length());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static List<Long> runEagerFilterBenchmark(NaturalArray array, TypeHandler typeHandler, int samples) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            List<Object> source = array.toList();
            List<Object> filtered = new ArrayList<Object>();
            for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
                Object value = source.get(sourceIndex);
                if (typeHandler.compare(value, Integer.valueOf(2)) > 0) {
                    filtered.add(value);
                }
            }
            consume(filtered.size());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static List<Long> runRegistryFilterSetupBenchmark(
        NaturalArray array,
        LiteralRegistry registry,
        ExecutionContext ctx,
        int samples
    ) {
        List<Long> out = new ArrayList<Long>();
        List<Object> args = new ArrayList<Object>();
        args.add(">");
        args.add(Integer.valueOf(2));
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            Object filtered = registry.handleMethod(array, "filter", args, ctx);
            consume(filtered.getClass().getName().length());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static List<Long> runEagerReduceBenchmark(NaturalArray array, TypeHandler typeHandler, int samples) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            List<Object> source = array.toList();
            Object accumulator = Integer.valueOf(0);
            for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
                accumulator = typeHandler.addNumbers(accumulator, source.get(sourceIndex));
            }
            consume(accumulator.toString().length());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static List<Long> runRegistryReduceBenchmark(
        NaturalArray array,
        LiteralRegistry registry,
        ExecutionContext ctx,
        int samples
    ) {
        List<Long> out = new ArrayList<Long>();
        List<Object> args = new ArrayList<Object>();
        args.add("+");
        args.add(Integer.valueOf(0));
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            Object reduced = registry.handleMethod(array, "reduce", args, ctx);
            consume(reduced == null ? 0 : reduced.toString().length());
            out.add(Long.valueOf(System.nanoTime() - start));
        }
        return out;
    }

    private static long medianNs(List<Long> values) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> copy = new ArrayList<Long>(values);
        java.util.Collections.sort(copy);
        return copy.get(copy.size() / 2).longValue();
    }

    private static double growthRatio(List<Long> values) {
        if (values == null || values.size() < 2) return 1.0d;
        double first = Math.max(1.0d, values.get(0).doubleValue());
        double last = Math.max(1.0d, values.get(values.size() - 1).doubleValue());
        return last / first;
    }

    private static void consume(int v) {
        if (v == Integer.MIN_VALUE) {
            throw new ProgramError("Unreachable");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException(message);
        }
    }

    private static final class SimpleEvaluator implements Evaluator {
        @Override
        public Object evaluate(Expr node, ExecutionContext ctx) {
            if (node == null) return null;
            if (node instanceof IntLiteral) return ((IntLiteral) node).value;
            if (node instanceof FloatLiteral) return ((FloatLiteral) node).value;
            if (node instanceof BoolLiteral) return Boolean.valueOf(((BoolLiteral) node).value);
            if (node instanceof TextLiteral) return ((TextLiteral) node).value;
            if (node instanceof NoneLiteral) return null;
            throw new ProgramError("SimpleEvaluator does not support expression: " + node.getClass().getSimpleName());
        }

        @Override
        public Object evaluate(cod.ast.node.Stmt node, ExecutionContext ctx) {
            throw new ProgramError("SimpleEvaluator does not support statement evaluation");
        }

        @Override
        public Object invokeLambda(Object callback, List<Object> arguments, ExecutionContext ctx, String ownerMethod) {
            throw new ProgramError("SimpleEvaluator does not support lambda invocation");
        }
    }
}
