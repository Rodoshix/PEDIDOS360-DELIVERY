package cl.duoc.pedidos360.kafkaadmin;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.ExposedPort;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Own network, loopback-only random port, tmpfs state; never starts or mounts the development stack. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named="P360_KAFKA_ADMIN_IT", matches="true")
class DisposableAdminIntegrationTests {
  final String run = UUID.randomUUID().toString();
  final String topic = "p360.disposable."+run+".sample";
  Network network;
  GenericContainer<?> zk;
  Broker broker;
  Admin admin;
  String servers, cluster;

  final class Broker extends GenericContainer<Broker> {
    Broker() {
      super(DockerImageName.parse("apache/kafka:3.9.2@sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02"));
      withNetwork(network);withNetworkAliases("kafka");withExposedPorts(19092);
      withLabel("p360.test.disposable","true");withLabel("p360.test.run-id",run);
      withEnv("LOG_DIR","/tmp/kafka-logs");withEnv("KAFKA_HEAP_OPTS","-Xms256m -Xmx512m");
      withTmpFs(Map.of("/var/lib/kafka/data","rw,size=256m,uid=1000,gid=1000",
          "/tmp","rw,size=64m,uid=1000,gid=1000", "/etc/kafka/secrets","rw,size=1m,uid=1000,gid=1000",
          "/mnt/shared/config","rw,size=1m,uid=1000,gid=1000"));
      withCreateContainerCmdModifier(cmd -> {
        cmd.withUser("1000:1000").withEntrypoint("/bin/sh","-c");
        cmd.getHostConfig().withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"))
            .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(19092)));
      });
      withCommand(new String[]{"while [ ! -f /tmp/p360-broker.properties ]; do sleep 0.1; done; exec /opt/kafka/bin/kafka-server-start.sh /tmp/p360-broker.properties"});
      waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
    }
    @Override protected void containerIsStarting(InspectContainerResponse info) {
      String cfg = "broker.id=1\nzookeeper.connect=zookeeper:2181\n"
          + "listeners=INTERNAL://0.0.0.0:9092,HOST://0.0.0.0:19092\n"
          + "advertised.listeners=INTERNAL://kafka:9092,HOST://127.0.0.1:"+getMappedPort(19092)+"\n"
          + "listener.security.protocol.map=INTERNAL:PLAINTEXT,HOST:PLAINTEXT\ninter.broker.listener.name=INTERNAL\n"
          + "log.dirs=/var/lib/kafka/data\nbroker.rack=p360-disposable-"+run+"\n"
          + "num.partitions=1\ndefault.replication.factor=1\nmin.insync.replicas=1\n"
          + "offsets.topic.replication.factor=1\noffsets.topic.num.partitions=3\n"
          + "auto.create.topics.enable=false\ndelete.topic.enable=true\nlog.retention.ms=86400000\n"
          + "unclean.leader.election.enable=false\nmessage.max.bytes=65536\n";
      // Docker archive extraction can write below tmpfs rather than into its live mount.
      // Write the generated, non-secret configuration inside the container namespace instead.
      String encoded=Base64.getEncoder().encodeToString(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try {
        var written=execInContainer("/bin/sh","-c","printf '%s' '"+encoded+"' | base64 -d > /tmp/p360-broker.properties");
        if(written.getExitCode()!=0)throw new IllegalStateException("Ephemeral broker configuration write failed");
        assertThat(execInContainer("test","-f","/tmp/p360-broker.properties").getExitCode()).isZero();
      } catch(Exception failure) { throw new IllegalStateException("Ephemeral broker configuration unavailable",failure); }

    }
  }

  @BeforeAll void start() throws Exception {
    network=Network.newNetwork();
    try {
      zk=new GenericContainer<>(DockerImageName.parse("zookeeper:3.8.4-jre-17@sha256:20d3c93ebda093d0c90911493396975e0e04523af225d8053d42bab781f9ca48"))
          .withNetwork(network).withNetworkAliases("zookeeper")
          .withLabel("p360.test.disposable","true").withLabel("p360.test.run-id",run)
          .withTmpFs(Map.of("/data","rw,size=64m,uid=1000,gid=1000","/datalog","rw,size=64m,uid=1000,gid=1000","/logs","rw,size=32m,uid=1000,gid=1000"))
          .withCreateContainerCmdModifier(cmd->{cmd.withUser("1000:1000");cmd.getHostConfig().withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges:true"));})
          .waitingFor(Wait.forLogMessage(".*binding to port.*\n",1).withStartupTimeout(Duration.ofSeconds(60)));
      zk.start();broker=new Broker();broker.start();
      assertIsolated(zk);assertIsolated(broker);
      servers="127.0.0.1:"+broker.getMappedPort(19092);
      var props=new Properties();props.put("bootstrap.servers",servers);props.put("default.api.timeout.ms","10000");props.put("request.timeout.ms","5000");
      admin=Admin.create(props);cluster=admin.describeCluster().clusterId().get(10,TimeUnit.SECONDS);
      new DisposableTopicAdmin(admin).verifyIsolation(request("test-describe",null,null,null));
      Files.writeString(Path.of("target","disposable-isolation.json"),
          "{\"runId\":\""+run+"\",\"clusterId\":\""+cluster+"\",\"bootstrap\":\""+servers+"\",\"networkId\":\""+network.getId()+"\",\"brokerContainer\":\""+broker.getContainerId()+"\",\"zookeeperContainer\":\""+zk.getContainerId()+"\",\"persistentMounts\":0}");
      System.out.println("ISOLATION VERIFIED run="+run+" cluster="+cluster+" endpoint="+servers+" persistentMounts=0");
    } catch(Exception failure) {stop();throw failure;}
  }

  void assertIsolated(GenericContainer<?> c) {
    var info=c.getDockerClient().inspectContainerCmd(c.getContainerId()).exec();
    assertThat(info.getConfig().getLabels()).containsEntry("p360.test.disposable","true").containsEntry("p360.test.run-id",run);
    assertThat(info.getHostConfig().getBinds()).isNullOrEmpty();
    assertThat(info.getMounts()).isEmpty();
    assertThat(info.getHostConfig().getTmpFs()).isNotEmpty();
    assertThat(info.getNetworkSettings().getNetworks()).hasSize(1);
    assertThat(info.getNetworkSettings().getNetworks().values().iterator().next().getNetworkID()).isEqualTo(network.getId());
  }

  @AfterAll void stop() {
    if(admin!=null)admin.close(Duration.ofSeconds(3));
    if(broker!=null)broker.close();if(zk!=null)zk.close();if(network!=null)network.close();
  }

  AdminRequest request(String command,String key,String value,String confirm) {
    return requestFor(command,topic,key,value,confirm);
  }

  AdminRequest requestFor(String command,String name,String key,String value,String confirm) {
    var args=new ArrayList<String>(List.of("--p360.kafka.admin.command="+command,
        "--p360.kafka.bootstrap-servers="+servers,"--p360.kafka.admin.run-id="+run,
        "--p360.kafka.admin.cluster-id="+cluster,"--p360.kafka.admin.topic="+name,
        "--p360.kafka.admin.disposable=true"));
    if(key!=null)args.add("--p360.kafka.admin.config="+key);
    if(value!=null)args.add("--p360.kafka.admin.value="+value);
    if(confirm!=null)args.add("--p360.kafka.admin.confirm="+confirm);
    return AdminRequest.parse(new MockEnvironment(),new DefaultApplicationArguments(args.toArray(String[]::new)));
  }

  void command(String operation,String key,String value,String confirm) throws Exception {
    var env=new MockEnvironment().withProperty("p360.kafka.admin.command",operation).withProperty("p360.kafka.bootstrap-servers",servers);
    var args=new ArrayList<String>();args.add("--p360.kafka.admin.command="+operation);args.add("--p360.kafka.bootstrap-servers="+servers);
    if(operation.startsWith("test-")) {
      var params=new LinkedHashMap<String,String>();params.put("disposable","true");params.put("run-id",run);params.put("cluster-id",cluster);params.put("topic",topic);
      if(key!=null)params.put("config",key);if(value!=null)params.put("value",value);if(confirm!=null)params.put("confirm",confirm);
      params.forEach((k,v)->{env.withProperty("p360.kafka.admin."+k,v);args.add("--p360.kafka.admin."+k+"="+v);});
    }
    new KafkaAdminApplication().adminCommand(env).run(new DefaultApplicationArguments(args.toArray(String[]::new)));
  }

  Config config(String name) throws Exception {
    var r=new ConfigResource(ConfigResource.Type.TOPIC,name);
    return admin.describeConfigs(List.of(r)).all().get(10,TimeUnit.SECONDS).get(r);
  }

  @Test @Order(1) void operationalCatalogCreatesReconcilesAndReadsEffectiveConfiguration() throws Exception {
    command("plan",null,null,null);assertThat(admin.listTopics().names().get()).isEmpty();
    command("apply",null,null,null);command("list",null,null,null);command("describe",null,null,null);
    assertThat(admin.listTopics().names().get()).containsAll(TopicCatalog.topics().stream().map(TopicCatalog.Spec::name).toList());
    String name=TopicCatalog.topics().getFirst().name();var r=new ConfigResource(ConfigResource.Type.TOPIC,name);
    admin.incrementalAlterConfigs(Map.of(r,List.of(new AlterConfigOp(new ConfigEntry("retention.ms","600000"),AlterConfigOp.OpType.SET)))).all().get();
    assertThat(config(name).get("retention.ms").value()).isEqualTo("600000");
    assertThat(new TopicReconciler(admin).reconcile(false)).anyMatch(x->x.startsWith("SET "+name));
    command("apply",null,null,null);assertThat(config(name).get("retention.ms").value()).isEqualTo("-1");
    assertThat(new TopicReconciler(admin).reconcile(true)).hasSize(4).allMatch(x->x.startsWith("UNCHANGED"));
  }

  @Test @Order(2) void disposableCreationConfigurationUpdateAndResetAreEffective() throws Exception {
    command("test-create",null,null,null);command("test-describe",null,null,null);
    assertThatThrownBy(()->command("test-update","retention.ms","120000",null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(config(topic).get("retention.ms").value()).isEqualTo("600000");
    assertThatThrownBy(()->command("test-update","retention.ms","120000","UPDATE:"+topic+":retention.ms:240000")).isInstanceOf(IllegalArgumentException.class);
    assertThat(config(topic).get("retention.ms").value()).isEqualTo("600000");
    command("test-update","retention.ms","120000","UPDATE:"+topic+":retention.ms:120000");
    assertThat(config(topic).get("retention.ms").value()).isEqualTo("120000");
    command("test-update","retention.ms","240000","UPDATE:"+topic+":retention.ms:240000");
    assertThat(config(topic).get("retention.ms").value()).isEqualTo("240000");
    command("test-update","cleanup.policy","compact","UPDATE:"+topic+":cleanup.policy:compact");
    assertThat(config(topic).get("cleanup.policy").value()).isEqualTo("compact");
    assertThatThrownBy(()->command("test-update","cleanup.policy","delete",null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(config(topic).get("cleanup.policy").value()).isEqualTo("compact");
    command("test-update","cleanup.policy","delete","UPDATE:"+topic+":cleanup.policy:delete");
    assertThat(config(topic).get("cleanup.policy").value()).isEqualTo("delete");
    command("test-update","cleanup.policy","compact","UPDATE:"+topic+":cleanup.policy:compact");
    command("test-update","segment.ms","60000","UPDATE:"+topic+":segment.ms:60000");
    assertThat(config(topic).get("segment.ms").value()).isEqualTo("60000");
    assertThat(config(topic).get("cleanup.policy").value()).isEqualTo("compact");
    command("test-reset-config","retention.ms",null,"RESET:"+topic+":retention.ms");
    assertThat(config(topic).get("retention.ms").source()).isNotEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
    assertThat(config(topic).get("retention.ms").value()).isEqualTo("86400000");
  }

  @Test @Order(3) void consumerGroupsOffsetsAndLagAreReadOnly() throws Exception {
    var pp=new Properties();pp.put("bootstrap.servers",servers);pp.put("key.serializer",StringSerializer.class.getName());pp.put("value.serializer",StringSerializer.class.getName());
    try(var producer=new KafkaProducer<String,String>(pp)){producer.send(new ProducerRecord<>(topic,"one","1")).get();producer.send(new ProducerRecord<>(topic,"two","2")).get();}
    String group="p360-admin-it-"+run;
    var cp=new Properties();cp.put("bootstrap.servers",servers);cp.put("key.deserializer",StringDeserializer.class.getName());cp.put("value.deserializer",StringDeserializer.class.getName());
    cp.put("group.id",group);cp.put("group.protocol","classic");cp.put("enable.auto.commit","false");cp.put("auto.offset.reset","earliest");cp.put("max.poll.records","1");
    try(var consumer=new KafkaConsumer<String,String>(cp)) {
      consumer.subscribe(List.of(topic));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);ConsumerRecords<String,String> records;
      do {records=consumer.poll(Duration.ofMillis(100));if(System.nanoTime()>deadline)throw new IllegalStateException("Test group did not consume");}while(records.isEmpty());
      consumer.commitSync();
    }
    var before=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
    assertThat(before).hasSize(1);assertThat(before.values().iterator().next().offset()).isEqualTo(1);
    var captured=new java.io.ByteArrayOutputStream();var original=System.out;
    try(var output=new java.io.PrintStream(captured,true,java.nio.charset.StandardCharsets.UTF_8)) {
      System.setOut(output);command("groups",null,null,null);
    } finally {System.setOut(original);}
    String inspection=captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(inspection).contains("GROUP "+group).contains("OFFSET "+group+" "+topic+"-0 committed=1 lag=1");
    System.out.print(inspection);
    assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get()).isEqualTo(before);
  }

  @Test @Order(4) void deletionOnlyRemovesOwnDisposableTopic() throws Exception {
    assertIsolated(broker);assertIsolated(zk);
    for(var spec:TopicCatalog.topics())
      assertThatThrownBy(()->requestFor("test-delete",spec.name(),null,null,"DELETE:"+spec.name())).isInstanceOf(IllegalArgumentException.class);
    for(String op:List.of("test-update","test-reset-config","test-delete")) {
      String confirm=op.equals("test-delete")?"DELETE:"+topic:op.equals("test-reset-config")?"RESET:"+topic+":retention.ms":"UPDATE:"+topic+":retention.ms:120000";
      var inherited=new MockEnvironment().withProperty("p360.kafka.admin.command",op)
          .withProperty("p360.kafka.bootstrap-servers",servers).withProperty("p360.kafka.admin.disposable","true")
          .withProperty("p360.kafka.admin.run-id",run).withProperty("p360.kafka.admin.cluster-id",cluster)
          .withProperty("p360.kafka.admin.topic",topic).withProperty("p360.kafka.admin.config","retention.ms")
          .withProperty("p360.kafka.admin.value","120000").withProperty("p360.kafka.admin.confirm",confirm);
      assertThatThrownBy(()->new KafkaAdminApplication().adminCommand(inherited).run(new DefaultApplicationArguments()))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit command");
      assertThatThrownBy(()->new KafkaAdminApplication().adminCommand(inherited).run(new DefaultApplicationArguments(
          "--p360.kafka.admin.command="+op,"--p360.kafka.bootstrap-servers="+servers,
          "--p360.kafka.admin.disposable=true","--p360.kafka.admin.run-id="+run,
          "--p360.kafka.admin.cluster-id="+cluster,"--p360.kafka.admin.topic="+topic,
          "--p360.kafka.admin.config=retention.ms")))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit");
      assertThat(admin.listTopics().names().get()).contains(topic);
      assertThat(config(topic).get("retention.ms").value()).isEqualTo("86400000");
    }
    command("test-delete",null,null,"DELETE:"+topic);
    assertThat(admin.listTopics().names().get()).doesNotContain(topic).containsAll(TopicCatalog.topics().stream().map(TopicCatalog.Spec::name).toList());
  }

  @Test @Order(5) @EnabledIfEnvironmentVariable(named="P360_KAFKA_CORE_REGRESSION",matches="true")
  void coreRegressionAgainstThisDisposableBroker() throws Exception {
    String mvn=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"mvn.cmd":"mvn";
    var builder=new ProcessBuilder(mvn,"-B","-ntp","-f","../../shared/p360-kafka-core/pom.xml","test");
    builder.environment().put("P360_KAFKA_IT","true");builder.environment().put("P360_KAFKA_PROCESSES_IT","true");
    builder.environment().put("P360_KAFKA_OUTAGE_IT","false");
    builder.environment().put("P360_KAFKA_IT_BOOTSTRAP_SERVERS",servers);
    builder.environment().put("P360_KAFKA_BOOTSTRAP_SERVERS",servers);
    var child=builder.redirectErrorStream(true).redirectOutput(Path.of("target","core-regression.log").toFile()).start();
    if(!child.waitFor(5,TimeUnit.MINUTES)) {
      child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();throw new IllegalStateException("Core regression timeout");
    }
    assertThat(child.exitValue()).isZero();
  }
}
