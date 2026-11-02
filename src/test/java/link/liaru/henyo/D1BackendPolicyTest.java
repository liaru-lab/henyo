package link.liaru.henyo;

import java.util.Map;

public final class D1BackendPolicyTest {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        require(D1BackendPolicy.architecture("Hexagon Arch version v81\nHTPT0:") == 81);
        require(D1BackendPolicy.architecture("Hexagon Arch version v79") == 79);
        for (String log : new String[]{"", "HTPT0:", "Hexagon Arch version v790",
                "Hexagon Arch version v85", "Hexagon Arch version v79\nHexagon Arch version v81",
                "Hexagon Arch version v999999999999999999999"})
            require(D1BackendPolicy.architecture(log) == 0);
        require(!D1BackendPolicy.accepts(D1BackendPolicy.LEGACY_CONTRACT, 81));
        require(D1BackendPolicy.accepts(D1BackendPolicy.LEGACY_CONTRACT, 79));
        require(D1BackendPolicy.accepts(D1BackendPolicy.CONTRACT, 81));
        require(D1BackendPolicy.accepts(D1BackendPolicy.CONTRACT, 79));
        require(!D1BackendPolicy.accepts(D1BackendPolicy.CONTRACT, 85));
        require(!D1BackendPolicy.accepts("unknown", 79));
        Map<String,String> conservative=D1BackendPolicy.environment(81);
        require("0".equals(conservative.get("GGML_HEXAGON_DMA64")));
        require(conservative.entrySet().stream().allMatch(e -> e.getValue().equals("0")));
        require(conservative.keySet().stream().noneMatch(k -> k.contains("HMX") || k.contains("GATE_LONG")));
        Map<String,String> stable=D1BackendPolicy.environment(79);
        require("1".equals(stable.get("HENYO_D1_PAIR_GLU")));
        require("1".equals(stable.get("HENYO_D1_SELECT_LAST")));
        require("adaptive".equals(stable.get("HENYO_D1_HMX_N")));
        require(!stable.containsKey("GGML_HEXAGON_ARCH"));
        require(!conservative.containsKey("GGML_HEXAGON_ARCH"));
        System.out.println("d1 backend generation/profile checks passed");
    }
}
