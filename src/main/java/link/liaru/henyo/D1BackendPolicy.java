package link.liaru.henyo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicitly validated accelerator generations and their numerical profiles. */
final class D1BackendPolicy {
    static final String LEGACY_CONTRACT = "henyo.d1.htp-v79/1";
    static final String CONTRACT = "henyo.d1.htp-v79-v81/1";
    private static final Pattern ARCH = Pattern.compile("Hexagon Arch version v([0-9]+)\\b");

    static int architecture(String log) {
        Matcher matches = ARCH.matcher(log);
        int found = 0;
        while (matches.find()) {
            int value;
            try { value = Integer.parseInt(matches.group(1)); }
            catch (NumberFormatException invalid) { return 0; }
            if (value != 79 && value != 81 || found != 0 && value != found) return 0;
            found = value;
        }
        return found;
    }

    static boolean accepts(String contract, int architecture) {
        return architecture == 79 && (LEGACY_CONTRACT.equals(contract) || CONTRACT.equals(contract))
            || architecture == 81 && CONTRACT.equals(contract);
    }

    static Map<String, String> environment(int architecture) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("GGML_HEXAGON_DMA64", "0");
        if (architecture == 79) {
            String[][] stable = {{"NPU_STENCIL","1"},{"PAIR_GLU","1"},{"PAIR_MAX_TOKENS","0"},
                {"SELECT_LAST","1"},{"SELECT_FFN","1"},{"SELECT_PAD","1"},{"SELECT_MIN_TOKENS","256"},
                {"RESIDUAL_NORM","1"},{"HMX_SCOPE","down"},{"HMX_M","0"},
                {"HMX_N","adaptive"},{"GATE_LONG_TUNE","1"}};
            for (String[] flag : stable) flags.put("HENYO_D1_" + flag[0], flag[1]);
        } else {
            // Probe without tuning; v81 retains the independently checked baseline.
            for (String name : new String[]{"NPU_STENCIL","PAIR_GLU","SELECT_LAST","RESIDUAL_NORM"})
                flags.put("HENYO_D1_" + name, "0");
        }
        return flags;
    }

    static String profile(int architecture) { return architecture == 79 ? "stable" : "conservative"; }
}
