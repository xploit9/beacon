package org.scilab.forge.jlatexmath;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Cypher integration added 2026-10-07 for pinned JLaTeXMath Android 0.2.0.
 * Formula definitions are local to one complete layout/raster operation. The
 * upstream engine is unchanged; its package-visible registries are accessed here.
 * New native client code is GPL-3.0-only; upstream notices remain packaged. */
public final class CypherMathDefinitions {
    private static final Object LOCK = new Object();
    private CypherMathDefinitions() { }

    public static <T> T render(Callable<T> formula) throws Exception {
        synchronized (LOCK) {
            // Initialize bundled commands/environments/colors/fonts before snapshotting.
            new TeXFormula("x");
            ColorAtom.getColor("black");
            Field settings = DefaultTeXFont.class.getDeclaredField("generalSettings");
            settings.setAccessible(true);
            Field font = JavaFontRenderingBox.class.getDeclaredField("font");
            font.setAccessible(true);
            Object originalFont = font.get(null);
            float originalSize = TeXIcon.defaultSize;
            float originalMagnification = TeXIcon.magFactor;
            float originalPixels = TeXFormula.PIXELS_PER_POINT;
            float originalScale = TeXFormula.FONT_SCALE_FACTOR;
            boolean originalLoading = TeXParser.isLoading;
            HashMap<String, String> originalCode = NewCommandMacro.macrocode;
            HashMap<String, String> originalReplacement = NewCommandMacro.macroreplacement;
            HashMap<String, MacroInfo> originalCommands = MacroInfo.Commands;
            return restoreRegistries(Arrays.asList(NewCommandMacro.macrocode,
                NewCommandMacro.macroreplacement, MacroInfo.Commands, MacroInfo.Packages,
                ColorAtom.Colors, TeXFormula.predefinedTeXFormulas,
                TeXFormula.predefinedTeXFormulasAsString, (Map<?, ?>) settings.get(null)), () -> {
                try {
                    MacroBudget budget = new MacroBudget();
                    BudgetedMacros pack = new BudgetedMacros(budget);
                    Method execute = BudgetedMacros.class.getDeclaredMethod("executeMacro", TeXParser.class, String[].class);
                    NewCommandMacro.macrocode = new DefinitionMap(originalCode, budget);
                    NewCommandMacro.macroreplacement = new DefinitionMap(originalReplacement, budget);
                    MacroInfo.Commands = new CommandMap(originalCommands, budget);
                    MacroInfo.Packages.put(NewCommandMacro.class.getName(), pack);
                    // Existing built-in macros must share the same guard as newly defined ones.
                    for (Map.Entry<String, MacroInfo> entry : originalCommands.entrySet()) {
                        MacroInfo old = entry.getValue();
                        if (old.pack instanceof NewCommandMacro && old.macro != null && old.macro.getName().equals("executeMacro")) {
                            MacroInfo bound = old.hasOptions ? new MacroInfo(pack, execute, old.nbArgs, old.posOpts) : new MacroInfo(pack, execute, old.nbArgs);
                            MacroInfo.Commands.put(entry.getKey(), bound);
                        }
                    }
                    return formula.call();
                }
                finally {
                    NewCommandMacro.macrocode = originalCode;
                    NewCommandMacro.macroreplacement = originalReplacement;
                    MacroInfo.Commands = originalCommands;
                    TeXParser.isLoading = originalLoading;
                    TeXIcon.defaultSize = originalSize;
                    TeXIcon.magFactor = originalMagnification;
                    TeXFormula.PIXELS_PER_POINT = originalPixels;
                    TeXFormula.FONT_SCALE_FACTOR = originalScale;
                    font.set(null, originalFont);
                }
            });
        }
    }

    /** This exact transaction also has a host regression without Android engine init. */
    public static <T> T restoreRegistries(List<? extends Map<?, ?>> registries, Callable<T> formula) throws Exception {
        synchronized (LOCK) {
            List<Runnable> restore = new ArrayList<>();
            for (Map<?, ?> registry : registries) restore.add(snapshot(registry));
            try { return formula.call(); }
            finally { for (int i = restore.size() - 1; i >= 0; i--) restore.get(i).run(); }
        }
    }

    private static <K, V> Runnable snapshot(Map<K, V> registry) {
        Map<K, V> original = new HashMap<>(registry);
        return () -> { registry.clear(); registry.putAll(original); };
    }

    /** Exact parser work ceilings; shared by all nested macros/environments in one render. */
    public static final class MacroBudget {
        public static final int MAX_DEFINITIONS = 128;
        public static final int MAX_EXPANSIONS = 1024;
        public static final int MAX_LOOKUPS = 4096;
        public static final int MAX_TEXT = 64 * 1024;
        public static final int MAX_REPLACEMENT_TEXT = 512 * 1024;
        private int definitions, expansions, lookups;
        private long replacementText;
        public void definition(String code) {
            if (++definitions > MAX_DEFINITIONS || code.length() > 4096) throw limit();
        }
        public void expansion() { if (++expansions > MAX_EXPANSIONS) throw limit(); }
        public void lookup() { if (++lookups > MAX_LOOKUPS) throw limit(); }
        public String replace(String source, String marker, String replacement) {
            if (source.length() > MAX_TEXT || replacement.length() > MAX_TEXT) throw limit();
            int matches = 0;
            for (int at = source.indexOf(marker); at >= 0; at = source.indexOf(marker, at + marker.length())) matches++;
            long length = source.length() + (long) matches * (replacement.length() - marker.length());
            if (length > MAX_TEXT || length > MAX_REPLACEMENT_TEXT - replacementText) throw limit();
            replacementText += length;
            return source.replace(marker, replacement);
        }
        public String result(String source) {
            if (source.length() > MAX_TEXT || source.length() > MAX_REPLACEMENT_TEXT - replacementText) throw limit();
            replacementText += source.length();
            return source;
        }
        private static IllegalArgumentException limit() { return new IllegalArgumentException("Math macro expansion exceeds the native formula budget"); }
    }

    private static final class DefinitionMap extends HashMap<String, String> {
        private final MacroBudget budget;
        DefinitionMap(Map<String, String> source, MacroBudget budget) { super(source); this.budget = budget; }
        @Override public String put(String name, String code) { budget.definition(code); return super.put(name, code); }
    }
    private static final class CommandMap extends HashMap<String, MacroInfo> {
        private final MacroBudget budget;
        CommandMap(Map<String, MacroInfo> source, MacroBudget budget) { super(source); this.budget = budget; }
        @Override public MacroInfo get(Object key) { budget.lookup(); return super.get(key); }
        @Override public MacroInfo put(String name, MacroInfo command) {
            if (command.nbArgs < 0 || command.nbArgs > 9) throw MacroBudget.limit();
            return super.put(name, command);
        }
    }

    /** Bounded adaptation of NewCommandMacro.executeMacro (Calixte DENIZET, 2009),
     * from pinned upstream 0.2.0; original GPL-2.0-or-later/linking-exception notices
     * are retained in licenses/jlatexmath-LICENSE.txt. Hermes changes 2026-10-07:
     * literal substitution is equivalent to quoteReplacement/replaceAll for #n,
     * and work/output limits are checked before replacement allocations. */
    public static final class BudgetedMacros extends NewCommandMacro {
        private final MacroBudget budget;
        BudgetedMacros(MacroBudget budget) { this.budget = budget; }
        @Override public String executeMacro(TeXParser parser, String[] args) {
            return substituteMacro(budget, macrocode.get(args[0]), macroreplacement.get(args[0]), args);
        }
    }

    /** Same literal/optional substitution path as the live parser's scoped pack. */
    public static String substituteMacro(MacroBudget budget, String code, String defaultOptional, String[] args) {
        budget.expansion();
        int count = args.length - 11;
        int offset = 0;
        String optional = args[count + 1];
        if (optional == null) optional = defaultOptional;
        if (optional != null) { offset = 1; code = budget.replace(code, "#1", optional); }
        for (int i = 1; i <= count; i++) code = budget.replace(code, "#" + (i + offset), args[i]);
        return budget.result(code);
    }
}
