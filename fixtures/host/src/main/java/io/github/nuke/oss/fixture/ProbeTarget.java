package io.github.nuke.oss.fixture;

// Ordinary Java methods. They contain no framework or NukeApp-OSS runtime dependency.
public final class ProbeTarget {
    private ProbeTarget() {}

    public static String greet(String name) {
        return "original:" + name;
    }

    public static String describe() {
        return "未检测到 NukeApp-OSS Hook。请检查框架、模块作用域并重启测试宿主。";
    }

    public static String setEnabled(boolean enabled) {
        return "未检测到 NukeApp-OSS Hook，开关没有应用。";
    }
}
