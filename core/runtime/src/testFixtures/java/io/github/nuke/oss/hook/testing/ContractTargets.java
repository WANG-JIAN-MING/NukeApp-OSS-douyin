package io.github.nuke.oss.hook.testing;

public final class ContractTargets {
    public static String echo(String value) { return value; }
    public static int number(int value) { return value; }
    public static void nothing() {}
    public static String fail() { throw new IllegalStateException("host-failure"); }
    public String instance(String value) { return "instance:" + value; }

    public static final class Constructed {
        public final String value;
        public Constructed(String value) {
            if (value == null) throw new IllegalStateException("construction-failed");
            this.value = value;
        }
    }
}
