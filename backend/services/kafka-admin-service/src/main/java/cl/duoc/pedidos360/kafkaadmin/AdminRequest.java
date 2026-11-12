package cl.duoc.pedidos360.kafkaadmin;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

/** Closed CLI vocabulary. Only parse can construct a request, preserving invocation provenance.
 * Validation precedes opening a network client. No arbitrary resource names. */
final class AdminRequest {
  private final String command, servers, runId, clusterId, topic, config, value, confirmation;
  private final boolean disposable;

  String command() { return command; }
  String servers() { return servers; }
  String runId() { return runId; }
  String clusterId() { return clusterId; }
  String topic() { return topic; }
  String config() { return config; }
  String value() { return value; }
  String confirmation() { return confirmation; }
  boolean disposable() { return disposable; }

  private static final Set<String> MUTATIONS = Set.of("apply", "test-create", "test-update", "test-reset-config", "test-delete");
  static final Set<String> COMMANDS = Set.of("list", "describe", "plan", "apply", "groups",
      "test-create", "test-describe", "test-update", "test-reset-config", "test-delete");
  static final Set<String> CONFIGS = Set.of("retention.ms", "cleanup.policy", "segment.ms");
  static final Set<String> FIELDS = Set.of("command", "run-id", "cluster-id", "topic", "config",
      "value", "confirm", "disposable");

  private AdminRequest(String command, String servers, String runId, String clusterId,
      String topic, String config, String value, String confirmation, boolean disposable) {
    this.command = command; this.servers = servers; this.runId = runId; this.clusterId = clusterId;
    this.topic = topic; this.config = config; this.value = value; this.confirmation = confirmation;
    this.disposable = disposable;
    if (command == null || !COMMANDS.contains(command)) throw new IllegalArgumentException("Unsupported operation");
    if (servers == null || servers.isBlank()) throw new IllegalArgumentException("Missing bootstrap servers");
    for (String endpoint : servers.split(",", -1)) {
      var address = Pattern.compile("(?:[A-Za-z0-9][A-Za-z0-9.-]*|\\[[0-9a-fA-F:]+\\]):([0-9]{1,5})").matcher(endpoint);
      if (!address.matches() || Integer.parseInt(address.group(1)) < 1
          || Integer.parseInt(address.group(1)) > 65535)
        throw new IllegalArgumentException("Invalid bootstrap endpoint");
    }
    if (command.startsWith("test-")) {
      if (!disposable || runId == null || !runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
        throw new IllegalArgumentException("Disposable run identity required");
      if (clusterId == null || !clusterId.matches("[A-Za-z0-9_-]{16,64}"))
        throw new IllegalArgumentException("Expected isolated cluster ID required");
      if (topic == null || !topic.matches("p360\\.disposable\\." + Pattern.quote(runId) + "\\.[a-z][a-z0-9-]{0,31}"))
        throw new IllegalArgumentException("Only this run's disposable topics are permitted");
      var endpoint = Pattern.compile("(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})").matcher(servers);
      if (!endpoint.matches()) throw new IllegalArgumentException("Disposable operations require one loopback endpoint");
      int port = Integer.parseInt(endpoint.group(1));
      if (port < 1024 || port > 65535 || port == 19092)
        throw new IllegalArgumentException("Development endpoint and privileged ports are forbidden");
      if (command.equals("test-update") || command.equals("test-reset-config")) {
        if (config == null || !CONFIGS.contains(config)) throw new IllegalArgumentException("Configuration outside allowlist");
      } else if (config != null) throw new IllegalArgumentException("Unexpected configuration parameter");
      if (command.equals("test-update")) validateValue(config, value);
      else if (value != null) throw new IllegalArgumentException("Unexpected configuration value");
      // All updates require consent: an apparently safe change may race with configuration changes.
      String required = command.equals("test-delete") ? "DELETE:" + topic
          : command.equals("test-reset-config") ? "RESET:" + topic + ":" + config
          : command.equals("test-update") ? "UPDATE:" + topic + ":" + config + ":" + value : null;
      if (!Objects.equals(required, confirmation)) throw new IllegalArgumentException("Exact operation confirmation required");
    } else if (disposable || runId != null || clusterId != null || topic != null || config != null
        || value != null || confirmation != null) throw new IllegalArgumentException("Unexpected operation parameters");
  }

  static void validateValue(String key, String value) {
    if (value == null) throw new IllegalArgumentException("Configuration value required");
    if (key.equals("cleanup.policy")) {
      if (!Set.of("delete", "compact", "compact,delete").contains(value))
        throw new IllegalArgumentException("Unsupported cleanup policy");
    } else {
      if (!value.matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid configuration number");
      long n = Long.parseLong(value);
      long max = key.equals("retention.ms") ? 604800000L : 86400000L;
      if (n < 60000 || n > max) throw new IllegalArgumentException("Configuration number outside bounds");
    }
  }

  static AdminRequest parse(Environment env, ApplicationArguments args) {
    if (!args.getNonOptionArgs().isEmpty()) throw new IllegalArgumentException("Positional arguments are forbidden");
    for (String option : args.getOptionNames()) {
      boolean known = option.equals("p360.kafka.bootstrap-servers")
          || option.startsWith("p360.kafka.admin.") && FIELDS.contains(option.substring("p360.kafka.admin.".length()));
      if (!known || args.getOptionValues(option) == null || args.getOptionValues(option).size() != 1)
        throw new IllegalArgumentException("Unsupported or repeated administrative parameter");
    }
    String command = option(args, "command");
    if (command == null) {
      command = env.getProperty("p360.kafka.admin.command", "describe");
      if (MUTATIONS.contains(command)) throw new IllegalArgumentException("Explicit command argument required for mutation");
    }
    boolean testMutation = MUTATIONS.contains(command) && command.startsWith("test-");
    String servers = testMutation ? required(args, "p360.kafka.bootstrap-servers")
        : argumentOrEnvironment(args, env, "p360.kafka.bootstrap-servers", "localhost:19092");
    String flag = field(args, env, "disposable", testMutation, "false");
    if (!Set.of("true", "false").contains(flag)) throw new IllegalArgumentException("Invalid disposable flag");
    String run = field(args, env, "run-id", testMutation, null);
    String cluster = field(args, env, "cluster-id", testMutation, null);
    String topic = field(args, env, "topic", testMutation, null);
    String config = field(args, env, "config", false, null, testMutation);
    String value = field(args, env, "value", false, null, testMutation);
    String confirm = field(args, env, "confirm", false, null, testMutation);
    if (testMutation) {
      if (command.equals("test-update") || command.equals("test-reset-config"))
        config = required(args, "p360.kafka.admin.config");
      if (command.equals("test-update")) value = required(args, "p360.kafka.admin.value");
      if (!command.equals("test-create")) confirm = required(args, "p360.kafka.admin.confirm");
    }
    return new AdminRequest(command, servers, run, cluster, topic, config, value, confirm, flag.equals("true"));
  }

  private static String option(ApplicationArguments args, String field) {
    var values = args.getOptionValues("p360.kafka.admin." + field);
    return values == null ? null : values.getFirst();
  }

  private static String required(ApplicationArguments args, String key) {
    var values = args.getOptionValues(key);
    if (values == null || values.size() != 1 || values.getFirst().isBlank())
      throw new IllegalArgumentException("Explicit " + key + " argument required for mutation");
    return values.getFirst();
  }

  private static String argumentOrEnvironment(ApplicationArguments args, Environment env, String key, String fallback) {
    var values = args.getOptionValues(key);
    return values == null ? env.getProperty(key, fallback) : values.getFirst();
  }

  private static String field(ApplicationArguments args, Environment env, String name, boolean mandatory, String fallback) {
    return field(args, env, name, mandatory, fallback, mandatory);
  }

  private static String field(ApplicationArguments args, Environment env, String name,
      boolean mandatory, String fallback, boolean cliOnly) {
    String key = "p360.kafka.admin." + name;
    if (mandatory) return required(args, key);
    if (cliOnly) { var values = args.getOptionValues(key); return values == null ? fallback : values.getFirst(); }
    return argumentOrEnvironment(args, env, key, fallback);
  }
}
