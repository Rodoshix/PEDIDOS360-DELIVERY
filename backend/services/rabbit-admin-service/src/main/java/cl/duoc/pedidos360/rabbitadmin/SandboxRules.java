package cl.duoc.pedidos360.rabbitadmin;

/** Fixed security boundary, independent of transport DTOs and configurable broker defaults. */
public final class SandboxRules {
    public static final String VHOST = "pedidos360-admin-demo";
    public static final String NAME = "p360\\.demo\\.[A-Za-z0-9][A-Za-z0-9_.-]{0,99}";
    public static final String KEY = "[A-Za-z0-9_.#*\\-]{0,128}";
    private SandboxRules() {}
    public static String name(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")) throw AdminFailure.validation();
        if (!name.matches(NAME)) throw AdminFailure.conflict();
        return name;
    }
    public static String key(String key) {
        if (key == null || !key.matches(KEY)) throw AdminFailure.validation();
        return key;
    }
}
